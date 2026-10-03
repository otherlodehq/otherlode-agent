---
status: accepted
---

# Both tiers register as retransformation-capable, so they run after every other agent's transformer

Decided on 2026-10-03 in a grilling session with Luke, alongside ADR 0052.

The method tier and the endpoint tier registered their transformers as not retransformation-capable, which puts them in the same group as JaCoCo's, AspectJ's and Spring's load-time weaver's, where the `-javaagent` order on the command line decides who runs first. With this agent first, JaCoCo receives woven bytes: it identifies a class by a CRC64 of the bytes it receives (`Instrumenter.java:75`, 0.8.13) and its report hashes the class file (`Analyzer.java:106`), so every woven class reads "Execution data for class X does not match" with zero coverage. The endpoint tier counts too, since the JAX-RS module weaves the adopter's resource classes. And this agent first is Gradle's default: `Test.jvmArgs` is copied ahead of the `jacoco` plugin's argument provider (`DefaultJavaForkOptions.copyTo`, 8.14), so an adopter following the testkit's setup loses their coverage report.

So both tiers register as retransformation-capable. The JVM calls every transformer that is not capable before every one that is, whatever the command-line order (`jvmtiExport.cpp:975`, jdk21u), so this agent always runs after JaCoCo and the weavers, sees their output as received bytes, and reads shape from the class file under ADR 0052. Nothing is retransformed at install. OpenTelemetry's agent sits in the same group.

## Considered options

- **Staying in the first group, with documentation and a warning.** The README would tell adopters to add the agent through an argument provider after the `jacoco` plugin, and `premain` would read the JVM's input arguments and warn when a known coverage agent comes later. Rejected: adopters get a broken coverage report and one log line, and Maven's ordering differs from Gradle's again.
- **A testkit Gradle plugin that orders the arguments.** Rejected: a new published artifact that only covers Gradle and only covers the testkit.

## Consequences

- Another agent's `retransformClasses` on a woven class now reaches this agent. A null answer would leave the class without its probe field, the JVM would refuse the schema change, and the other agent's whole batch would fail. So a class this agent wove is woven again, from the plan its first weave stored (amended below), and any class this agent did not weave gets null. The `<clinit>` prelude does not run again and needs not to, since the field keeps its array. OpenTelemetry handles the same case by re-adding the same fields (`FieldBackedImplementationInstaller.java:250`, v2.32.0).
- Type descriptions on a retransformation are built from the passed bytes, not the loaded class, which already carries `$otherlodeProbeCounts`; ByteBuddy's default description strategy reads the loaded class.
- A retransformation commits nothing to the registry and declares no JAX-RS endpoint a second time.
- A redefinition is handled like a retransformation, since a transformer is handed the same arguments for both: refused when the class file changed, woven from the stored plan when it did not. ADR 0005 is amended to match.
- The JVM keeps an off-heap copy of each woven class's received bytes, about one class-file length each, freed when the class unloads. Before this, the only class it kept was the JDK lambda factory ADR 0035 hooks. There is no flag: the cost is bounded by the adopter's own code, like the branch tier's, and `STATUS.md`'s overhead measurement covers it. The second JVMTI environment already existed, since the manifest sets `Can-Retransform-Classes`.
- A capable agent listed ahead of this one that rewrites adopter classes is an earlier transformer like any other, handled by ADR 0052 at first load and by the stored plan on a retransformation.

## Amended on 2026-10-03: a re-weave follows the first weave's stored plan

The first version of this record assumed the bytes the JVM passes on a retransformation are the received bytes of the first load, and told a retransformation from a redefinition by hashing them. The review of the chunk that built it disproved the premise. HotSpot caches the input to the first retransformation-capable environment that changed the class (`jvmtiExport.cpp:1021-1035`, jdk21u), which can be another agent's, listed earlier; that agent runs again on every retransformation, and a live debugger or dynamic instrumentation produces different output by design. The hash then missed, this agent returned null, and the JVM refused the other agent's whole batch: a regression against the non-capable registration. The review also showed a re-weave that re-read a changed class file could keep the layout hash while moving counts to the wrong outcome.

So the first weave stores its plan per class: the layout hash, the probe count, the `<clinit>` slot, each method's entry-probe slot, each paired method's run of branch slots together with its tracked instructions and its dropped, throwing-default and unprobed ordinals as the class-file analysis gave them, the omission slots, and a hash of the class file when there was one. On any later call for a class this agent wove:

- the class file is readable now, the first weave read one too, and the two differ (a recompile into a classes directory, a jar rewritten in place, an IDE's HotSwap, which writes the class file first): the call is refused with a per-class WARNING naming the cause, and the JVM refuses the redefinition or retransformation, since the bytes lack the probe field;
- otherwise the bytes that arrived are paired against the stored instructions and woven with the stored plan, with no fresh analysis and nothing renumbered: a method that still pairs counts into its stored slots with the polarity it has at this call, and a method that no longer pairs gets no branch probes, so its branch counts freeze, with one INFO line per class.

The received bytes decide only which methods still pair, so an earlier agent's changed output is just an earlier transformer, including for a class the loader serves no class file for. A class file that has gone missing, or one that appears for a class first woven from memory, is no evidence of changed code and refuses nothing. Considered and rejected: keeping the received-bytes gate and listing earlier capable agents as a known gap, since it breaks another vendor's feature where the non-capable registration did not; and recomputing the analysis on each call, since it reads other classes' files (an enum switch's mapping class) whose changes could alter a method's slot count.

A redefinition that keeps the class file unchanged is accepted. That is the common HotSwap shape outside local development, a remote debug session or an application run from a jar, where the new code never reaches the class file. A method edited that way but keeping the same sequence of jumps (a changed constant, two like conditions swapped) counts into slots whose lines and conditions describe the code as first loaded: the manifest keeps describing the class file, not the redefined bytes.

The plan is held per woven class for its loader's life, compacted into arrays indexed by method rather than maps keyed by name; its measured size is in `STATUS.md`.
