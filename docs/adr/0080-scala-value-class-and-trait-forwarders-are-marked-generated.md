---
status: accepted
---

# Scala value-class forwarders and trait forwarders are marked generated

Decided on 2026-10-31 in a grill during the pre-release review. Amends 0048, and 0023's line on a trait's forwarder `t$`.

The review ran the real analyser over classes compiled with scalac 2.13.15 and 3.3.4. Two shapes scalac writes for every adopter still reached the never-hit list as the adopter's code.

**A value class.** For `implicit class RichInt(private val x: Int) extends AnyVal { def doubled: Int = x * 2 }`, scalac puts the body in the companion's `RichInt$.doubled$extension(I)I`. The class `RichInt` gets a boxed instance method `doubled()I` whose body reads `RichInt$.MODULE$`, loads the class's one field, loads its arguments and calls `doubled$extension`. An unboxed call site calls `doubled$extension` directly, so the boxed method stays at zero while the adopter's `def` runs, and it carries the `def`'s line. The analyser reported `doubled()I`, and the boxed `hashCode` and `equals` with their `$extension` twins, as ordinary methods. A case value class (`case class Id(value: String) extends AnyVal`) adds its case-class plumbing on both sides, about 13 methods each, and none of it matched ADR 0048's rules.

**A trait forwarder.** For `trait Greets { def greet(n: String) = "hi " + n }` and `class Greeter extends Greets`, scalac 2.12 and later, and every Scala 3 release, write `Greeter.greet` as a call to the trait's static `Greets.greet$` with the receiver and arguments, and `Greets.greet$` as an `invokespecial` of the trait's default `greet`. `Greeter.greet` carries the class's declaration line. When `greet` never runs on a `Greeter`, `Greeter.greet` was claimed dead, though the adopter wrote no such method and cannot delete it. ADR 0023 left the trait's `t$` to a collector name policy, which was never built, and ADR 0048 rejected name policies.

## The design

- **`VALUE_CLASS` also covers Scala value classes.** A Scala value class is a final class carrying a `Scala` or `ScalaSig` attribute, with one instance field, whose companion `X$` declares the `$extension` methods (scalac 2.12 suffixes an overloaded one's name with a number, as in `plus$extension0`). Its boxed instance method is marked when its body is exactly what scalac writes: `getstatic X$.MODULE$`, `aload_0` and the field read (a `getfield`, or a call to the field's accessor, which scalac mangles for a private `val`), each argument loaded in order, `invokevirtual X$.<same name>$extension` with the field's type followed by the method's own parameter types, and a return. The boxed `hashCode` and `equals` are marked the same way, and so are their `$extension` twins in `X$` when the body is scalac's own.
- **A case value class's plumbing is `CASE_CLASS`** on both sides: each boxed member by the forwarder shape above, and each `$extension` twin by its own exact body. Those bodies differ from a case class's: `canEqual` tests the boxed field class in Scala 2, `hashCode` is the field's own hash and no MurmurHash3 fold, `equals` has no reference shortcut, and Scala 3's `productElement` calls `_1$extension`. The companion's `apply` and `unapply`, and `copy$default$1` with its twin, are marked with them, as ADR 0048 marks a case class's companion members and `copy$default$N`.
- **A value-class forwarder passes calls through** to its `$extension` method, as a generated forwarder does under ADR 0041. The adopter's own `m$extension` holds the code and is never marked.
- **A new `GeneratedBy` value, `TRAIT_FORWARDER = 12`.** Additive on the wire. A class's instance method is marked when its body is exactly `aload_0`, each argument loaded in order, `invokestatic T.<same name>$` on an interface `T` among the class's supertypes with `T` prepended to the parameter types, and a return. Here `T` must declare that static method and a default method with the instance method's own name and descriptor. The trait's static `m$` is marked when its body is exactly `aload_0`, each argument loaded, `invokespecial T.m` and a return. Both pass calls through to the trait's default `m`, which holds the code and is never marked.
- **Two new `UnreadShape` families, `UNREAD_SHAPE_VALUE_CLASS = 9` and `UNREAD_SHAPE_TRAIT_FORWARDER = 10`.** Additive on the wire. The value-class outline is a boxed method whose companion declares its `m$extension` twin, and the companion's `hashCode$extension` and `equals$extension`. The trait-forwarder outline is a method with a trait's static `m$` twin and default `m` whose body calls that `m$`, and a trait's static `m$` with a default `m` twin. An adopter's override with a body of its own calls no `m$`, so it stays ordinary code on every release; one that adds work around a `super` call is in the outline, and is an unread shape where the release is not read.
- **Recognised by exact body, per read release.** The shapes are read with `javap -c` from fixtures in `fixtures-scala2` and `fixtures-scala3`, compiled with every Scala 3 release the agent reads and the Scala 2 releases ADR 0048's survey covers. A body that fails its rule is ordinary code in a Scala 3 class whose `.tasty` names a read release, and an unread shape otherwise (ADR 0054).

## Considered options

- **`STATIC_FORWARDER` for trait forwarders.** Rejected: the class's forwarder is an instance method, and a label that says "static" misleads, as ADR 0048 found when it kept `writeReplace` out of `CASE_CLASS`.
- **`DEFAULT_IMPLS` for trait forwarders.** Rejected: it names Kotlin's `$DefaultImpls` class, a different mechanism.
- **A name policy at the collector or the server.** Rejected, as in ADR 0026 and ADR 0048: a name cannot tell a hand-written method from compiler output.
- **A Scala-only value for value classes.** Rejected: `VALUE_CLASS` names the language feature both compilers share, and a consumer judges on "not `NONE`".

## Consequences

- The server labels `GeneratedBy` 12 `trait_forwarder` and the two unread families `value_class` and `trait_forwarder`. Until it does, it renders each as `unknown` and still leaves the probe out of every claim, since it judges on "not `NONE`".
- The collector's bindings are bumped to the schema that carries value 12.
- `VALUE_CLASS`'s comment in the proto names Scala value classes, and `methods-and-branches.md` lists both shapes.
- The trait's static default-getter accessor `t$default$N$` keeps ADR 0023's treatment.
- An adopter's override that only calls `super.m` compiles to the class forwarder's bytes and is marked, the way ADR 0078 marks a Kotlin override with a stub's bytes. A hand-written final one-field class whose companion holds an `m$extension` called with the forwarder's exact body is marked too.
