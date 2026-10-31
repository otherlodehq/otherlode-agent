---
status: accepted
---

# Probes reach their array through a dynamic constant, or an accessor below Java 11

Decided on 2026-10-04 in a grilling session. Amends 0004.

A woven class kept its counts in a `public static final long[]` the `<clinit>` prelude filled, and every probe read that field. Initialising a class initialises its superclass first, and its superinterfaces that declare default methods (JVMS 5.5), and that supertype's `<clinit>` may construct the class or call its static methods before the class's own `<clinit>` has run. The probe then read null and threw `NullPointerException` inside the adopter's code, leaving the class erroneous for the life of the JVM. Reproduced with the agent jar on 2026-10-04: a Java superclass constant holding a subclass instance (`static final Money ZERO = new Usd(0)`, with `Usd` used first), an interface constant holding an anonymous implementation, a Kotlin sealed class or sealed interface whose companion holds a subtype instance, and an enum constant body initialised by reflection. Entry probes, branch probes and the omission advice were all exposed; the `<clinit>` probe, which runs after the prelude, was not.

From class-file version 55 (Java 11), each probe site loads the array with `ldc` of a dynamic constant whose bootstrap method lives in `OtherlodeProbeArrays`, which the bootstrap loader makes reachable from every class, and which returns the registered array for the class name, layout hash, probe count and the lookup class's loader. A dynamic constant resolves on first use, independently of class initialisation, so there is no window. Such a class gets no field, no prelude and no added method: only its probe instructions differ from its class file.

Below version 55, the class keeps the field and the prelude, and every probe site calls a private static synthetic accessor instead of reading the field: it returns the field, or, while the field is still null, a second private method's call to `OtherlodeProbeArrays.resolve`, which returns the same registered array the prelude stores later (the registry key is class name, layout hash and loader identity, committed before the class is defined, 0005 and 0007). The slow path is a method of its own so the accessor stays within C1's inlining stack limit. An interface below version 52 cannot have private methods and has no probe outside `<clinit>`, so it gets no accessor. This is JaCoCo's split: 0.8.13 uses a dynamic constant from version 55 and, below it, an accessor that fetches without storing for interfaces.

## Considered options

- **Skipping the count when the array is null.** Rejected: an enum constant body's constructor runs once, inside its enum's `<clinit>`, so it would read as never hit forever, as would a branch outcome taken only then.
- **A sentinel array in the field before `<clinit>`.** Not possible: the JVM sets a static field to its default during preparation (JVMS 5.4.2), and only a primitive or `String` can carry a `ConstantValue`.
- **The fallback inlined at every probe site.** Works, at about 23 bytes per site against 3, which presses on the code-size limits `CodeSizeLimitsTest` gates.
- **The accessor alone, at every version.** Rejected: one mechanism, but every probe pays a call until the JIT inlines it (34.6 to 68 ns a probe under `-Xint` in the prototype), and every class carries added members, which a redefinition must reproduce exactly.

## Consequences

- Probe sites stay the same size: `ldc_w`, `getstatic` and `invokestatic` are each 3 bytes.
- In compiled code both forms cost the same as the field read: C2 treats a resolved dynamic constant as a constant, and inlines the accessor and folds the trusted static final, null check included (0.44 ns a call before and after in the prototype).
- Most adopter code compiles to version 61 or later (Spring Boot 3 and 4 require Java 17; Kotlin's Gradle plugin targets the toolchain), so most woven classes keep their class file's shape, which also leaves another agent's redefinition (0053) nothing added to reproduce.
- Both forms are woven identically on a re-weave from the stored plan (0053); a test covers a redefinition of each.
- A version 55 class has no field whose absence would make the JVM reject unwoven bytes, so a re-weave refused because the class file changed hands back the received bytes plus a synthetic `$otherlodeRefused` field, which the JVM rejects as a schema change, keeping 0053's outcome.
- A forked-JVM test reproduces every crashing shape above at versions 52 and 65 and checks the counts taken during the supertype's initialisation.
- The documentation that called a null field impossible (`OtherlodeProbeArrays`' Javadoc, `ProbeArrayInitializer`'s KDoc) is corrected with the change.

## Amendment, 2026-10-05: the members below 55 are written in ASM

The mechanism changed, the substance did not. A decoration refuses `defineField`, `defineMethod`
and `initializer`, so the below-55 field, the `<clinit>` prelude (prepended to an existing
initializer, or a new `static <clinit>` holding only the prelude), the accessor and its slow path,
and ADR 0053's refusal marker are written by `ProbeArrayMembers` as ASM events with the same names,
flags and instructions ByteBuddy wrote. The class literal is `ldc` from version 49 and
`Class.forName` below it; the accessor's stack map frame is written from version 50 only, since
HotSpot reads none below it. The rebase's appended `<clinit>` scaffold (a jump to an appended tail
with two frames) is gone, so the prelude adds no frame at all.

## Amended on 2026-10-31: the field is private, and a class without a `<clinit>` gets none

Found in the pre-release review. `ObjectStreamClass` computes a `Serializable` class's default `serialVersionUID` from every field that is not private and static, and from whether the class has a static initializer. The public field, and the `<clinit>` added to a class that had none, changed the default UID of every `Serializable` class below version 55 that declares none. Data one JVM serialized then failed to read on a JVM that weaves the class differently, with `InvalidClassException`. The public field also showed in `Class.getFields()`.

- **The field is private.** A class that has its own `<clinit>` gets `private static final synthetic long[] $otherlodeProbeCounts`, filled by the prelude as before, so C2 still folds the trusted final.
- **A class with no `<clinit>` gets none.** Its field is `private static synthetic`, not final, and the slow path stores the array it resolves into the field, so later calls read the field. `resolve` returns the same registered array on every call, so two threads that race store the same array.
- **An interface keeps `public static final`**, which the JVM requires of an interface field, and keeps its added `<clinit>`. A `Serializable` interface below version 55 that is woven, one with a default method, a static method or a static initialiser, therefore still changes its own default UID. That shows only when its `Class` object is serialized across a woven and an unwoven JVM. Skipping such interfaces would lose their counts to cover a case that rare, so the compatibility page states the exception instead.
- **The cost.** The field read in a class with no `<clinit>` is a plain static read and a null check, not a folded constant.

## Amended on 2026-10-31: a loader that cannot see the holder gets no woven class

Found in the pre-release review. A woven class reaches `OtherlodeProbeArrays` through its own defining loader (JVMS 5.3). A loader that does not delegate `dev.otherlode.bootstrap` to the boot loader, as JBoss Modules and Apache Felix do by default, fails that reference at the class's first probe or in its `<clinit>`, and the application breaks. Before the method tier weaves a class of a non-null loader, it checks once per loader that the loader resolves the holder to the agent's own class. A class of a loader that does not is left unwoven and reported skipped, and the agent logs one WARNING per loader naming the package to delegate. The endpoint tier shares the check: a module that would weave a class of such a loader switches itself off for the process, with `TRANSFORM_FAILED`. Skipping per loader instead would leave routes it declared in other loaders counted where its advice runs and never counted where it cannot, which reads as never called. OpenTelemetry's agent meets the same constraint by instrumenting the loaders; the check is the smaller step that fails closed.
