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

## Amended on 2026-10-26: settled while building the Kotlin shapes

The review found that the stub and forwarder rules also matched overloads the adopter wrote with a `super` call, which compile to the same body. Bytecode alone separates them as follows.

- **Types by position.** A stub's or forwarder's parameters and result must be the types the called `$DefaultImpls` method takes and returns once the caller's type arguments are put in. The called method's own generic signature (`<T:…>(LI<TT;>;TT;)TT;`) says which type argument each position takes. Type variables resolve through the class, its enclosing method and its outer classes. A position also matches through the stub's own signature, which covers a class kotlinc regenerates from a reified inline function.
- **No boxing twin.** At a nullable type argument the signature reads as at the plain one. kotlinc then writes the stub that passes the box, so a candidate that boxes for a call another candidate makes without boxing is the adopter's.
- **Open stubs, undeclared forwarders.** kotlinc writes a class's stub open and declares nothing in a sub-interface for the defaults it forwards. A stub must not be final, and a forwarder to a super-interface must not be one its sub-interface declares.
- **The static for an inherited default.** A value class that inherits an interface default `m` gets a static `m-impl` that boxes and calls `m`. It is marked when the class has no boxed forwarder `m` of its own. Its line table cannot decide it, since kotlinc 2.1 and earlier give it the class declaration's lines.
- **Open overloads stay marked.** An overload that is open compiles to a stub's exact bytes: one declared `open`, one that overrides another interface's member, or one the all-open or kotlin-spring plugin opens. Only `kotlin.Metadata`, which lists declared functions and not fake overrides, tells them apart. Whether to read it is open (STATUS).

## Amended on 2026-10-27: settled while building the kotlinx.serialization rules

- **`serializer()` by owner and descriptor.** The rule above read the body against the plugin's five shapes. The review found the plugin writes more (a sealed interface's companion, a generic sealed or abstract class from 2.2, a generic `with` class), each left unmarked. The plugin refuses to compile a `serializer()` the adopter wrote with its descriptor in a serializable class's companion or a serializable object: compiled on 1.9.25, 2.2.21 and 2.2.21 under `-language-version 1.9` for an object, a sealed interface's companion, a generic class, a generic abstract class, a generic `with` class and a `@MetaSerializable` class. So a `serializer()` taking one `KSerializer` per type parameter there is the plugin's whatever its body. `generatedSerializer()` keeps its body check.
- **Serializable.** A class carrying `@Serializable`, or an annotation whose class carries `@MetaSerializable`. A serializable companion of a plain class is its own owner.
- **A companion, not another nested class.** The owner holds the companion in a static field under its simple name, and the candidate has kotlinc's companion outline: a final class that is no enum, abstract class or interface, whose constructors are a private no-argument one and at most one other, a synthetic one taking a `DefaultConstructorMarker` and carrying no `Deprecated` attribute. That held for 760 companion class files read across the six builds, the agent's own classes and expect/actual sources. A nested class, interface, enum or sealed class the adopter holds in a companion property of the same name, whether its backing field is private, `@JvmField` or `lateinit`, lacks that outline. An object with its own `INSTANCE` must itself be serializable.
- **Lazy lambdas.** A lambda stored by `AASTORE` counts only when the next `PUTSTATIC` is `$childSerializers`.
- **Limits.** The rule rests on the plugin having compiled the class: a `@Serializable` class built without it can hold a `serializer()` the adopter wrote, which is marked. An annotation with `SOURCE` retention is not in the class file, so the plugin's `serializer()` in a class it makes serializable is left unmarked. A final nested class whose only constructor is a private no-argument one, held in a companion property of its own name, passes the companion check.

## Amended on 2026-10-27: open overloads are told apart by line

Decided in a grill, from `javap` of stubs, forwarders and adopter overloads compiled with kotlinc 1.9.25, 2.1.21, 2.2.21 and 2.4.20 under `-jvm-default=disable`, and with 2.2.21 and 2.4.20 in their default mode. It settles the open-overload question the first amendment left.

- **A stub starts on the class's header line.** kotlinc gives every class stub a first line-number entry on the line where its class's declaration starts: the `class` line, or the first annotation above it. No method of the class starts on an earlier line. An adopter's own method starts on its own declaration line, which comes later.
- **The rule.** A class stub, in every variant the first amendment reads (mangled, boxing, `Unit`-dropping, suspend), is marked only when its first line entry equals the smallest first line entry of any method in its class. A stub with no line table stays marked.
- **Forwarders keep their rules.** A sub-interface's `$DefaultImpls` forwarder needs no line check: the first amendment's rule that a sub-interface does not declare the method is already exact. The forwarders `$DefaultImpls` keeps under `-jvm-default=enable` must not get one: each starts on its own default's line, and on 2.4.20 an inherited one has no line table. The value-class static `m-impl` keeps its own rule, since no source can name a method `m-impl`.
- **No line table, still marked.** A class compiled without line numbers has nothing to compare, and its stubs and open overloads stay marked, as the first decision accepts for data-class and value-class members.
- **The hand-written exact override is the adopter's.** ADR 0026 marked an override whose body is only `super.m()` under ADR 0048, since its bytes are a stub's. It sits on its own line, so the rule leaves it unmarked. Its claim says it never ran, which is true, and deleting it leaves the default to run.
- **Limits.** A class written on one line, and a `$DefaultImpls` class whose earliest method is the adopter's overload, still have it marked. The second needs a sub-interface that inherits no default it does not override.

Considered and rejected:

- **Decode `kotlin.Metadata`.** Exact, but it brings back what ADR 0021 refused: a shaded library or a second protobuf decoder, and a metadata version that must keep pace with adopters' compilers.
- **Compare with the constructor's first line.** kotlinc 2.1.21 starts a constructor on the `Name(` line when the header spans lines, while the stubs keep the header's first line.
- **Keep the limit.** A bytecode tell exists, and the hidden overload is code the adopter wrote.
