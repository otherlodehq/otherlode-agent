---
status: accepted, amended by 0060
---

# Initialise probe arrays through a bootstrap-resident holder

The `:bootstrap` module contains one Java class, `OtherlodeProbeArrays`. The agent embeds it in its jar as `META-INF/otherlode/bootstrap-jar.bin` and appends it to the bootstrap classloader at `premain`. Each instrumented class gets a `<clinit>` prelude that fills `$otherlodeProbeCounts` with one call to `OtherlodeProbeArrays.resolve(className, layoutHash, probeCount, classLoader)`, every argument a constant. The agent plugs its registry in through `OtherlodeProbeArrays.install(Resolver)`. Because the holder sits on the bootstrap loader, a class defined by any loader can reach it from its own initializer.

## Considered options

Setting a private non-final field reflectively after load, through ByteBuddy's `LoadedTypeInitializer.ForStaticField`. This cannot work for interfaces: the JVM allows only public static final fields on an interface, and the JDK refuses reflective writes to static finals, so every interface with a default or static method failed to transform and was skipped. The holder covers classes and interfaces with one mechanism. ByteBuddy's `InitializationStrategy` is `NoOp`, so nothing runs after load and ByteBuddy does not inject its `Nexus` class through `Unsafe`.

## Consequences

- A miss in the holder (no resolver installed, nothing registered under that key) returns a fresh array of the right size and logs once. The worst case is an uncounted class, never an exception inside an application method.
- If the holder cannot be installed at all (a read-only filesystem, a full `java.io.tmpdir`), the agent logs at ERROR and disables itself entirely, exporter included. A missing instance is visible at the collector; a zero-hit instance is not.
- HotSpot prints a one-line CDS warning at startup about the appended bootstrap classpath. OpenTelemetry's agent causes the same one.
- The shadow plugin explodes any embedded `.jar` it copies, which is why the resource has a `.bin` suffix. The `verifyAgentJar` task fails the build if the resource is missing or a holder class is present loose.

Amended 2026-10-04 by 0060. The prelude alone left a window: a supertype's `<clinit>` can run the class's code before the class's own `<clinit>` fills the field, and the probes read null. From class-file version 55 the probes load the array as a dynamic constant bootstrapped from this holder, and the class gets no field or prelude; below 55 they call an accessor that falls back to `resolve` while the field is null. The holder is the one mechanism under both.

## Amended on 2026-10-15: a class in a named module

The holder sits in the boot loader's unnamed module, and nothing in the module graph gives an explicit named module a read edge to it, so a class there reaches the holder only through an edge added at runtime. The method tier adds one itself when it transforms a class whose module does not read the holder's yet, before it hands back the woven bytes (ByteBuddy's `assureReadEdgeTo`). OpenTelemetry's agent does the same (`ExposeAgentBootstrapListener`).

HotSpot adds the edge too, but not reliably enough to lean on. When an agent changes a class's bytes, `ClassFileParser::fill_instance_klass` sets the module's `has_default_read_edges` flag, and only the thread that set it then calls `jdk.internal.module.Modules.transformedByAgent`, which adds the Java-level reads to the boot and app loaders' unnamed modules. VM-level resolution passes on the flag alone (`ModuleEntry::can_read`), but a dynamic constant's bootstrap is a method handle constant, checked by `Module.canRead` in Java (`VerifyAccess.isClassAccessible`). A second woven class in the same module that resolves its dynamic constant before the upcall returns fails with `IllegalAccessError`, and keeps failing after the edge lands. The JVM also drops any exception the upcall throws, after the flag is set. Read in jdk17u, jdk21u and jdk25u, and reproduced on JDK 21 with the upcall held under a debugger.

So the edge is part of weaving, not of the JVM. If adding it throws, the class is not woven and is reported as skipped, and nothing is registered for it. The endpoint tier adds no edge for an adopter's module: its advice reaches the seam only through `invokestatic` and field access, which the VM checks against the flag, and it adds its edges for boot modules (`jdk.httpserver`, `java.base`) before it weaves anything. `NamedModuleWeavingTest` pins that a woven class of either probe-array form loads, runs and counts in a named module, that the method tier's edge is there before the JVM's could be, that a class whose edge cannot be added is skipped with nothing registered, and that an unwoven class's module gets no edge. Only the dynamic constant form needs the method tier's edge; the accessor form reaches the holder through `invokestatic`. The edge is visible through `Module.canRead`, which the JVM changes for every agent's transformed classes anyway. Only HotSpot JDKs are tested.
