---
status: accepted
---

# Kotlin delegation forwarders and `@JvmStatic` proxies are marked generated

Decided on 2026-11-09 in a grill after the code review of that day. Amends 0026 and 0048's line that `STATIC_FORWARDER` is a Scala shape. The bodies below were read on 2026-11-10 with `javap -c -p -l` from the kotlinc matrix fixtures, compiled with 1.9.25, 2.1.21, 2.2.21 and 2.4.21, the 2.x releases in K1 mode, and each `-jvm-default` mode.

The review ran the analyser over classes compiled by kotlinc and found two shapes kotlinc writes for every adopter that still reached the never-hit list as the adopter's code.

**A delegation forwarder.** For `class Wrapped(inner: Describer) : Describer by inner`, kotlinc keeps the delegate in a field and writes one public method per interface member the class does not override. The body reads the field, loads the arguments and calls the same name and descriptor on the field's type. The method is public, not synthetic, not a bridge, and has no line-number table. When callers use only some of the interface's members, each unused forwarder read as a dead method nobody wrote and nobody can delete. A scan of the Ktor, kotlinx, Javalin and Micronaut jars in the build's cache found 124 such methods.

**A `@JvmStatic` proxy.** For a `@JvmStatic` function in a companion object, kotlinc keeps the body in the companion's class and adds a static method on the outer class that reads the companion and calls it. Kotlin callers call the companion's method, so the proxy stays at zero while the function runs.

## The design

- **A new `GeneratedBy` value, `CLASS_DELEGATION = 13`.** Additive on the wire. A method of a class that carries Kotlin metadata and implements at least one interface is marked when it is public, not static, not synthetic, not a bridge and has no line-number table, and its body is exactly what kotlinc writes:
  - a null check for each non-null reference parameter, in parameter order: `aload <n>`, `ldc "<name>"`, `invokestatic Intrinsics.checkNotNullParameter`. A setter's check names `<set-?>`. A nullable parameter gets none, and neither does a `suspend` forwarder, which is a plain forward with no state machine;
  - `aload_0` and a `getfield` of the delegate field;
  - each argument loaded in order;
  - one call of the method's own name and descriptor on the field's declared type, `invokeinterface` for an interface and `invokevirtual` for a class, as `by Impl()` and `by object : I {}` give;
  - the matching return.
- **The delegate field is private and final, and is one of two.** kotlinc names it `$$delegate_<n>` when the class delegates to an expression or to a plain constructor parameter. When the class delegates through a constructor property, as in `class C(private val inner: I) : I by inner`, every release reuses that property's private final field and writes no `$$delegate_<n>`.
  - A forwarder that reads `$$delegate_<n>` is marked by the body above alone. Kotlin source cannot name that field in practice.
  - A forwarder that reads any other field is marked only when the field's declared type is an interface the class implements directly, and the class has line numbers at all, which is read from a constructor of it having a line-number table. kotlinc gives every constructor one, and a tool that strips line numbers strips them from the whole class. Without the last two, a hand-written `fun find(id: String) = dao.find(id)` in a class whose debug information was stripped has the same body, and a review showed it marked.
- **A delegation forwarder passes calls through** to the method it calls, as a generated forwarder does under ADR 0041. The delegate's own method holds the code and is never marked by this rule.
- **A `@JvmStatic` proxy is `STATIC_FORWARDER`.** That mark already means a static method on an object's class that only calls the same method on the object. The proxy is public, static and not synthetic, in a class whose Kotlin metadata says it is a class and not a file facade or a multi-file part. Its body is exactly a `getstatic` of the class's companion field, each argument loaded in order, an `invokevirtual` of the same name and descriptor on the companion's class, and the return, with no null check. The companion field is static and final, has the companion class's type and carries the companion's name, `Companion` or the name in `companion object Factory`. kotlinc 1.9.25 gives the proxy no line-number table. From 2.1 on it gives one entry, at the return instruction. So the proxy is marked when no line entry sits before its return. A method you wrote has its first line entry at its first instruction. The proxy passes calls through, as `STATIC_FORWARDER` already does.
- **A method you wrote is never marked.** `override fun describe() = inner.describe()` has a line-number table, and so does a hand-written static that calls the companion. In a class compiled without line numbers, a hand-written forwarder that reads a field named `$$delegate_<n>` cannot be told apart, as ADR 0026 accepts for a data class.

## Considered options

- **`TRAIT_FORWARDER` or `DEFAULT_IMPLS` for delegation forwarders.** Rejected: each names another mechanism, and a label that names the wrong one misleads the reader, as ADR 0080 found for `STATIC_FORWARDER`.
- **A new value for the `@JvmStatic` proxy.** Rejected: `STATIC_FORWARDER` describes it exactly, and a consumer judges on "not `NONE`".
- **Any private final field of the call's type as the delegate field**, with no further guard. Built first and rejected in review: it marked a hand-written method that calls the same name on a field, in a class that delegates nothing, once the class had no line numbers.
- **An unread-shape family for a forwarder whose body is not read.** Not built. No Kotlin forwarder mark has one (`DEFAULT_IMPLS`, `JVM_OVERLOADS`), and the compiler canary reads each new kotlinc release. A forwarder a later kotlinc writes differently reads as ordinary code until the agent reads it. The family can be added later without a break.
- **Leaving the `@JvmStatic` proxy as it is**, on the ground that "the annotation is unused" is a true observation. Rejected: the row names a method the adopter did not write, and the function it stands for is alive.

## Consequences

- The collector's bindings and the server need the new value before the server can name it. Until then the server stores it as a mark it does not know, which it never judges, so no claim is made from it.
- A delegated interface's default members are forwarded with the same body under every `-jvm-default` mode, with no `$DefaultImpls` stub in the delegating class, so they are marked by this rule. A forwarder whose name kotlinc mangled for a value class has the same body and is marked.
- Two shapes stay unmarked and read as never-hit methods. `STATUS.md` lists both. A forwarder specialised for a type argument, as in `class StringFeed(inner: Feed<String>) : Feed<String> by inner`, calls another descriptor and adds a `checkcast` or a boxing call. A class that delegates through a property typed as a sub-interface, `class ViaSub(private val inner: Sub) : I by inner`, reads a field whose type the class does not implement directly.
- `docs/site/methods-and-branches.md` lists both rules. The testkit's `GeneratedBy` gains `CLASS_DELEGATION`.
