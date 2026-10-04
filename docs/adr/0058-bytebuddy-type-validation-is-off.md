---
status: accepted
---

# ByteBuddy's type validation is off, and a verifier test guards the woven bytes

Decided on 2026-10-04 in a grilling session with Luke, after the overhead ceiling run (PetClinic REST on Spring Boot 4.1.1, `includePackages=org.springframework`) skipped 312 of 4462 matched classes.

The agent builds its `ByteBuddy` with `TypeValidation.DISABLED`, as Elastic's APM agent does. One switch controls two checks in byte-buddy 1.18.12: `InstrumentedType.Default.validated()` (called from `MethodRegistry.java:534`), which checks the described type's names, modifiers, supertypes and annotation targets, and `TypeWriter.ValidatingClassVisitor` (`TypeWriter.java:2441`), which rejects bytecode illegal for the class-file version. Every check either one made on this agent's classes fired on the adopter's own bytes, which the JVM had already accepted, and never on anything the agent writes: the `long[]` field, the `<clinit>` prelude (whose class constant ByteBuddy already emits as `Class.forName` below Java 5, `ClassConstant.java:184`), the inlined entry and omission probes, and the branch probes. Fixtures at class-file versions 45 to 69 wove, passed verification and ran with validation on and off. Turning it off recovered, in PetClinic, 90 spring-data-jpa classes compiled by ajc (which copies a `TYPE_USE` annotation such as jspecify's `@Nullable` into the declaration attribute, failing its `@Target` check), two anonymous classes whose receiver type validation could not resolve, and three classes that named an absent optional type only inside `validated()`; it also lets a `@file:JvmName` class weave, the case 0007 was written for.

The agent's copy of ByteBuddy's annotation check (`TypeMatchPolicy.unsafeAnnotation`, used by `isSafeToInstrument` and the static baseline) goes with it. It had copied half the rule: ByteBuddy also accepts an `ANNOTATION_TYPE` annotation on an annotation type (`InstrumentedType.java:1759-1760`), so `@Target`, `@Retention` and `@Documented` made the copy turn away every annotation type, which the ceiling run reported as 166 skipped classes with no log line, and the baseline put in its statically unsafe bucket. Annotation types fall through to the ordinary rules: nothing to probe, or a `<clinit>` probe when one has a static initialiser. The baseline's `statically_unsafe_classes` field is removed from the wire and reserved, with the collector's bindings and the server's bucket, while nothing is published (0057).

What replaces the guard is a test in `check`: every class in the benchmark corpora and in a fixture matrix of class-file versions 45 to 69 (classes, pre-Java-8 interfaces with a static initialiser, annotation types with one, subroutine code, annotations below version 49) is woven, then defined and initialised in a fresh loader, and must define and initialise whenever its unwoven twin does, with no `VerifyError` or `ClassFormatError`. Validation checked the described type; the test checks the bytes the JVM receives.

## Considered options

- **Keep validation and fix the copied check.** Rejected: it recovers only the annotation types, which had nothing to probe, and leaves the ajc and `@JvmName` classes skipped.
- **Keep validation and ignore only its annotation failures**, by wrapping ByteBuddy's instrumented-type factory so a `validated()` failure whose message starts "Cannot add @" is passed over. Prototyped and it works for the ajc classes, but it depends on the wording of ByteBuddy's messages and on a proxy around its internals, and it does not recover the receiver-type cases or old class files carrying annotations.
- **A frozen instrumented type**, as OpenTelemetry and Datadog use, which never calls `validated()`. Not possible: a frozen type cannot take a field or an initializer (`InstrumentedType.java:2225`, `:2358`), and 0003 depends on both.

## Consequences

- A mistake in the agent's own woven code for an old class-file version now fails the adopter's class at definition rather than leaving it uninstrumented. The verifier test exists to find that mistake before an adopter does.
- Every remaining skip comes from `TransformResultListener` and logs one WARNING per class, so an adopter's log names every skipped class.
- Pre-Java-6 subroutine code (`jsr`/`ret`) failed whatever the setting, because the branch tier makes ASM compute frames. ASM's `JSRInlinerAdapter` runs first on class files below version 50.
- 51 classes in the ceiling run still fail while ByteBuddy describes a field or method whose type is an absent optional dependency (`InstrumentedType.java:465`, `:467`). Describing a missing type as a placeholder is being tried; `STATUS.md` holds the outcome.
