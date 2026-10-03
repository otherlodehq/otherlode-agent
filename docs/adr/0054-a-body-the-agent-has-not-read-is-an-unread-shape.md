---
status: accepted
---

# A body the agent has not read is an unread shape, never the adopter's code

Decided on 2026-10-03 in a grilling session with Luke, after ADRs 0052 and 0053 removed JaCoCo as a cause of the symptom.

Every exact-body rule fell back to "the adopter's code" when it could not recognise a body, and the adopter's code that never ran is a never-hit row. For generated plumbing that is the silent, confident, wrong finding ADR 0007 rules out, and it was already in the field. The agent's real matcher, run over scalac output from 2.12.18 to 3.10.0-RC3, showed ordinary patch releases changing the bodies ADR 0048 reads. From 2.13.17, 3.3.7 and 3.7.1, every case class's `hashCode` reads as dead code. 3.9.0, the new LTS line, keeps 609 of the 819 marks 3.3.4 gets. Every change comes from the compiler, not the library: a 2.13.16 compiler against the 2.13.17 library writes 2.13.16 bodies. ADR 0048's "a body from another version stays unmarked" was a treadmill that patch releases turn.

So there is a third answer besides "generated" and "the adopter's". An **unread shape** is a probe whose code has the outline of compiler output but whose body matches no shape the agent has read for that compiler. It is probed and counted, it is never a finding or a cluster node, and it is listed apart with the family the agent could not read. It is a statement about the agent, not about who wrote the code.

## The design

- **Outlines.** An unread shape needs an outline that says "this is where compiler output goes". The outline is what keeps the rule from swallowing hand-written code.
  - Scala: a plumbing family's name and descriptor, in a class passing ADR 0048's case-class test, in its companion, or in a module class (`writeReplace`, `readResolve`). Also Scala 3 enum plumbing, read under ADR 0048 as amended, and a static method whose `$` twin declares the same name and descriptor.
  - Kotlin multi-file facade: any method of a `k = 4` class that is not a recognised forwarder, since a facade holds no adopter code.
  - Coroutine machinery: a jump or switch in a suspend-shaped method, fed by `getfield label` on a continuation class or by the `getCOROUTINE_SUSPENDED` value, that matches none of ADR 0025's shapes.
  - String switch lowering: when ADR 0038 cannot read a lowering, the collision side of the last `equals` check in each bucket of a switch on `String.hashCode()`. Every other site of that lowering stays a plain numeric site, since it is reachable.
- **Version-keyed where the class names its compiler.** A Scala 3 top-level class's `.tasty` resource carries the exact compiler release (`Scala 3.9.0`) in its header, matched to the class by the UUID in its `TASTY` attribute. A nested, module, local or anonymous class takes its top-level class's. When the release is on the agent's read list (ADR 0055), a body that fails its rule is hand-written and ordinary, as ADR 0048 says. When it is not on the list, the body is an unread shape.
- **Version-blind where it does not.** A Scala 2 class, or a Scala 3 class with no `.tasty`, says only 2.12 or 2.13 (its `ScalaSig` pickle version). kotlinc's `kotlin.Metadata.mv` is the language version, not the compiler: 2.2.21 at `-language-version 2.1` writes the `mv` of 2.1.21 and different bytecode. javac's class-file version follows `--release`, and javac 21 and 25 at `--release 17` write identical bytes. In each case a body inside an outline that matches no read shape is an unread shape. For Scala 2 that includes a hand-written `toString` or `equals` override in a case class, which lands in the unread list instead of never-hit: the price of not knowing the compiler. The other outlines are shapes no source produces.
- **No outline, no unread shape.** Data-class and enum marks go by name and descriptor, so they cannot drift. `@JvmOverloads` and `$DefaultImpls` forwarders have no outline: Java can write either by hand, and a `$DefaultImpls` body is real code under `-jvm-default=disable`. Kotlin's and javac's shapes were identical from kotlinc 1.9.25 to 2.4.20 and javac 17 to 25, apart from the gaps ADR 0026's amendment closes.
- **Wire.** A `oneof origin { GeneratedBy generated_by = 16; UnreadShape unread_shape; }` on `ProbeLocation`, the same on `DeclaredMethod`, and `oneof { RoutineKind routine = 7; UnreadShape unread_shape; }` on `BranchOutcome`. Each default is the adopter's code. `UnreadShape` values are `UNREAD_SHAPE_CASE_CLASS`, `_STATIC_FORWARDER`, `_SCALA_OBJECT`, `_SCALA_ENUM`, `_MULTIFILE_FACADE`, `_COROUTINE_MACHINERY` and `_SWITCH_LOWERING`. `ResourceAttributes` gains `agent_version`, since an unread shape is a fact about one agent version.
- **Propagation.** A branch site inside an unread-shape method carries the method's unread shape, as a site inside a generated method carries its mark (ADR 0026). An omission probe carries its target's, as it carries the target's mark and inline flag.
- **Consumers.** The testkit's `neverHit()` leaves unread shapes out, and `neverHitUnreadShapes()` lists them, the way `neverHitRoutineOutcomes()` lists routine outcomes. They are not cluster nodes in the testkit, the server or the stub collector. They make no never-supplied or always-supplied claim. The server's report counts them per family and the UI labels the row.
- **The agent says so.** One WARNING per class with a version-keyed unread shape, naming the compiler release. One summary on the first flush, counting unread shapes per family, naming each unread release it knows, and saying to upgrade the agent. Its version-blind part says that hand-written Scala 2 overrides land there too.

## Considered options

- **Keep the adopter's-code default and read versions as they appear**, ADR 0048's stance. Rejected: patch releases broke the rules three times in a year of Scala 3 alone, and every gap between a release and an agent that reads it is a false finding at the adopter.
- **Mark by name** in a class that looks generated. Rejected for ADR 0048's reason: it hides hand-written code and claims "generated" with no evidence.
- **Loosen the rules to structure**, such as any `hashCode` that calls only `MurmurHash3`, `Statics` or `ScalaRunTime` and reads only the elements. Rejected: it trades a visible, labelled gap for an invisible over-mark of a hand-written method that mixes the same fields differently.
- **Version-blind everywhere.** Rejected: where the class names its compiler, a mismatch on a read release is known to be hand-written, and calling it unread throws that away.
- **Telling Scala 2 hand-written methods from generated ones by line table.** Rejected: it is the rule ADR 0048 dropped after four review rounds, because placement follows the adopter's layout.
- **The library's `library.properties` as a stand-in for the Scala 2 compiler.** Rejected: the bodies come from the compiler, Scala 3.3 to 3.7 ship a 2.13 library unrelated to the compiler, and a newer runtime library would mislabel the code.
- **Two independent fields, or `GeneratedBy` plus an `unread` bool.** Rejected once Luke set correctness over compatibility, since there are no consumers yet: both can represent a probe that is generated and unread at once. A single `Origin { kind, family }` was rejected too: recognised coroutine machinery has no probe, so `COROUTINE_MACHINERY` is never valid with kind generated, trading one invariant for another.
- **A compiler field on `ClassLocation`.** Rejected for now: only Scala 3 could fill it, and the family plus `agent_version` tell the adopter what to do. The agent's WARNING names the release. Adding it later is additive.
- **A class-level flag for a class analysed from its received bytes** (ADR 0052's no-class-file case). Rejected: it would abstain on every class defined from memory, including the common one with no earlier transformer, for a case that needs three rare things together. The agent logs an INFO count of such classes on the first flush instead.

## Consequences

- Unread shapes are counted, not dropped, so a Scala 3.10 `hashCode` that is called still shows its hits, and an agent upgrade that reads the release turns the row into a marked one with its history intact.
- A Scala 2 codebase's never-called hand-written case-class override is listed as unread, not as a finding. The summary says so.
- `lazy val` plumbing, Kotlin value classes and kotlinx.serialization have no outline and still read as the adopter's code. Each waits for an adopter, as ADRs 0026 and 0048 record.
- The testkit's `neverHitUnreadShapes()`, the `UnreadShape` enum, the two oneofs and `agent_version` are new public surfaces, listed for the testkit and wire review in `STATUS.md`'s pre-release checklist item 4.
- `otherlode-collector`'s bindings bump with the schema, since its redaction strips fields it does not know.
