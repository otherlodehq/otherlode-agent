---
status: accepted
---

# Kotlin delegation forwarders and `@JvmStatic` proxies are marked generated

Decided on 2026-11-09 in a grill after the code review of that day. Amends 0026 and 0048's line that `STATIC_FORWARDER` is a Scala shape.

The review ran the analyser over classes compiled by kotlinc and found two shapes kotlinc writes for every adopter that still reached the never-hit list as the adopter's code.

**A delegation forwarder.** For `class Wrapped(inner: Describer) : Describer by inner`, kotlinc keeps the delegate in a field `$$delegate_0` and writes one public method per interface member the class does not override. The body reads the field, loads the arguments and calls the same name and descriptor on the interface. The method is public, not synthetic, not a bridge, and has no line-number table. When callers use only some of the interface's members, each unused forwarder read as a dead method nobody wrote and nobody can delete. A scan of the Ktor, kotlinx, Javalin and Micronaut jars in the build's cache found 124 such methods.

**A `@JvmStatic` proxy.** For a `@JvmStatic` function in a companion object, kotlinc keeps the body in `Outer$Companion.m` and adds a static `Outer.m` that reads `Outer.Companion` and calls it. Kotlin callers call the companion's method, so the proxy stays at zero while the function runs.

## The design

- **A new `GeneratedBy` value, `CLASS_DELEGATION = 13`.** Additive on the wire. A method is marked when the class carries Kotlin metadata, the method is public, not static, not synthetic, not a bridge and has no line-number table, and its body is exactly what kotlinc writes: the parameter null checks kotlinc adds, then `aload_0`, a `getfield` of a field of the class named `$$delegate_<n>`, each argument loaded in order, an `invokeinterface` of the same name and descriptor on the field's type, and a return. The bodies are read with `javap -c` from the kotlinc matrix fixtures, for every release in the matrix.
- **A delegation forwarder passes calls through** to the interface method it calls, as a generated forwarder does under ADR 0041. The delegate's own method holds the code and is never marked by this rule.
- **A `@JvmStatic` proxy is `STATIC_FORWARDER`.** That mark already means a static method on an object's class that only calls the same method on the object. The proxy is that method for a Kotlin companion: a static method whose body is exactly a `getstatic` of the class's `Companion` field, each argument loaded in order, an `invokevirtual` of the same name and descriptor on `<Outer>$Companion`, and a return, with no line-number table. It passes calls through, as `STATIC_FORWARDER` already does.
- **A method you wrote is never marked.** `override fun describe() = inner.describe()` has a line-number table, and so does a hand-written static that calls the companion. In a class compiled without line numbers the two cannot be told apart, as ADR 0026 accepts for a data class.

## Considered options

- **`TRAIT_FORWARDER` or `DEFAULT_IMPLS` for delegation forwarders.** Rejected: each names another mechanism, and a label that names the wrong one misleads the reader, as ADR 0080 found for `STATIC_FORWARDER`.
- **A new value for the `@JvmStatic` proxy.** Rejected: `STATIC_FORWARDER` describes it exactly, and a consumer judges on "not `NONE`".
- **An unread-shape family for a `$$delegate_<n>` forwarder whose body is not read.** Not built. No Kotlin forwarder mark has one (`DEFAULT_IMPLS`, `JVM_OVERLOADS`), and the compiler canary reads each new kotlinc release. A forwarder a later kotlinc writes differently reads as ordinary code until the agent reads it. The outline is crisp, so the family can be added later without a break.
- **Leaving the `@JvmStatic` proxy as it is**, on the ground that "the annotation is unused" is a true observation. Rejected: the row has no line, names a method the adopter did not write, and the function it stands for is alive.

## Consequences

- The collector's bindings and the server need the new value before the server can name it. Until then the server stores it as a mark it does not know, which it never judges, so no claim is made from it.
- `docs/site/methods-and-branches.md` lists both rules. The testkit's `GeneratedBy` gains `CLASS_DELEGATION`.
