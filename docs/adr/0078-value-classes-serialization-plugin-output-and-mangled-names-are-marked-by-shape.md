---
status: accepted
---

# Value classes, serialization plugin output and mangled names are marked by shape

Decided on 2026-10-26 in a grill, from compiler output read with kotlinc 1.9.25, 2.1.21, 2.2.21 and 2.4.20. It amends ADR 0026. The shapes in each rule below were read off `javap` output for every one of those releases, and were the same on all four except where a rule says otherwise.

## Context

ADR 0026 marks compiler output by bytecode shape. Four kinds of Kotlin output had no rule, so each method read as the adopter's code, and a never-called one became a dead-code claim about code the adopter cannot delete:

- A value class's members: the static `equals-impl`, `equals-impl0`, `hashCode-impl` and `toString-impl`, and the boxed `equals`, `hashCode` and `toString` that forward to them.
- A data class with a value-class property. kotlinc mangles the name of each `componentN` and of `copy` whose signature holds the value class (`component1-TIrSkvE`, `copy-D9vhfF4`), so the class failed the data-class rule and none of its methods was marked.
- A stub or `$DefaultImpls` forwarder that boxes a value class through `box-impl` and unboxes the result, one that drops a `Unit` result (`pop; return`), and a suspend default's forwarder that returns `Unit` after comparing the result with the suspended marker.
- The kotlinx.serialization plugin's output: the nested `$serializer` class and the companion's `serializer()`. `serializer()` is often never called, since a property's or a sealed subclass's serializer is read from `INSTANCE` directly.

The research also found a hole in ADR 0026's rule. A plain class with a hand-written `component1`, `component2` and `copy` had them marked `DATA_CLASS`, since only `equals`, `hashCode` and `toString` were checked for a line-number table.

## Decision

- **`VALUE_CLASS`, a new `GeneratedBy` value.** A class is a value class when it declares a synthetic static `box-impl` and a synthetic `unbox-impl`. No source can declare a synthetic method. In it:
  - `equals-impl`, `equals-impl0`, `hashCode-impl` and `toString-impl` are marked when they are static and have no line-number table. A hand-written `toString` puts its body in `toString-impl` with a line table, so it stays the adopter's.
  - A non-synthetic instance method is marked when its body only reads the class's one field, loads its arguments, calls the static with its own name plus `-impl` (or its own name when that is already mangled), and returns. It is a pass-through.
  - `constructor-impl` and the property getter stay unmarked: they stand for the adopter's declarations.
- **`DATA_CLASS` accepts mangled names.** `componentN` and `copy` may carry a `-` and seven characters from `[A-Za-z0-9_-]`. The hash is not checked: it names the signature, not the author, and a plain class gets the same names.
- **`componentN` and `copy` also need no line table.** This closes the hole above. As for `equals`, `hashCode` and `toString`, a class compiled without line numbers has all of them marked.
- **`DEFAULT_IMPLS` takes three more shapes** in its stub and forwarder rules: a name that adds a `-` and seven characters to the called name; an argument boxed by `box-impl` of a value class and a result unboxed by `checkcast` and `unbox-impl`; and a `Unit` result dropped with `pop; return` when the called method returns `Object`. A suspend forwarder is marked when, after the forwarding call, it compares the result with `COROUTINE_SUSPENDED` and returns `Unit`, with both descriptors ending in `Continuation;)Object`. It must cast its receiver to the interface, since an adopter's `override suspend fun run() = super.run()` in a sub-interface compiles to the same tail without the cast.
- **`KOTLINX_SERIALIZATION`, a new `GeneratedBy` value.**
  - A `$serializer` class is the plugin's when it implements `kotlinx.serialization.internal.GeneratedSerializer`, has a static `INSTANCE` of its own type or, for a generic class, a `typeSerial0` field, and carries `@Deprecated` with level `HIDDEN`. In it, `serialize`, `deserialize`, `childSerializers`, `getDescriptor`, `typeParametersSerializers`, the private `<init>` and `getTypeSerialN` are marked. A method with another name stays the adopter's. Under the K2 frontend the class is synthetic and not probed at all; the rule is for K1, which 1.9.25 and every 2.x under `-language-version 1.9` use.
  - The companion's `serializer()`, or an object's, is marked when the outer class carries `@Serializable`, its descriptor takes one `KSerializer` per type parameter, and its body is one of the plugin's five: read `$serializer.INSTANCE`, read a `with` object's `INSTANCE`, construct a `with` class, construct a generic `$serializer` from its argument, or call `get$cachedSerializer`. An adopter cannot write a `serializer()` that clashes: the plugin fails the compile. `generatedSerializer$<module>()` is marked the same way.
  - A class-form lambda that builds the lazy cached serializer is marked when the outer class's `<clinit>` passes its `INSTANCE` to `LazyKt.lazy` and stores the result in `$cachedSerializer$delegate` or `$childSerializers`.
  - In a `@Serializer(forClass = ...)` object, the serializer interface's methods are an unread shape (ADR 0054). Nothing tells the plugin's members from the adopter's under K2. Their line tables cannot either, since the plugin gives its members the declaration's first line.
- **The canary reads K1 too.** Per ADR 0055, it compiles the serialization fixtures with 1.9.25 and with each 2.x release under `-language-version 1.9`, since the frontend, not the release, decides whether `$serializer` is synthetic.
- **No metadata.** Every rule reads bytecode. K1 records the plugin's members as `SYNTHESIZED` in `kotlin.Metadata`, but K2 does not, and ADR 0026 keeps the agent off metadata.

## Considered options

- **Verify the mangling hash.** Rejected: it is an MD5 of the parameter and return types, so it says nothing about who wrote the method, and checking it needs each nested class's dotted Kotlin name.
- **A body table per value-class member.** Rejected: the bodies differ with the underlying type (`Integer.hashCode`, `Intrinsics.areEqual`, `Arrays.hashCode`, another value class). The line-table check gives the same answer with one rule.
- **Read `SYNTHESIZED` from `kotlin.Metadata` under K1.** Rejected: exact under K1 only, and it would make the agent read metadata for one family.
- **Mark a `@Serializer(forClass)` object's interface methods.** Rejected: it would hide an override the adopter wrote.
- **One `GeneratedBy` value named `SERIALIZATION`.** Rejected: it reads as Java serialization, which the agent does not mark.

## Consequences

- `GeneratedBy` gains two values, `VALUE_CLASS` and `KOTLINX_SERIALIZATION`. The testkit's enum gains them too. A server that does not name a value keeps it out of findings, since any non-zero mark is not judged, but labels it as ordinary code until it learns the name.
- A class compiled without line numbers has its data-class and value-class members marked, as ADR 0026 accepts for `equals`, `hashCode` and `toString`.
- An override that compiles to exactly a generated stub's bytes is marked, as ADR 0048 accepts.
