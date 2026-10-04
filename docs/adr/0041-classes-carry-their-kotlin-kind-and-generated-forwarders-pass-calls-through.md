---
status: accepted
---

# Classes carry their Kotlin kind, and generated forwarders pass calls through

Decided on 2026-09-25 with `otherlode-server`, whose ADR 0035 holds how a file facade reads.

`DemoServerMainKt` headed tables and graph groups, but the source has top-level functions in `DemoServerMain.kt` and no class of that name. Only a guess from the `Kt` suffix could tell, and `@file:JvmName` breaks the guess. Kotlin writes the kind of every class it compiles into the `k` element of `@kotlin.Metadata`: 1 for a class, 2 for a file facade, 3 for a synthetic class, 4 for a multi-file facade and 5 for a multi-file part. A multi-file facade, from `@file:JvmMultifileClass`, holds only forwarders to the parts where the code lives, so every function showed twice.

## The design

- **The kind on the wire.** `ClassLocation` and the baseline's `DeclaredClass` carry `KotlinKind`: `NONE` for a class with no `@kotlin.Metadata`, `KOTLIN_CLASS`, `FILE_FACADE`, `MULTIFILE_FACADE`, `MULTIFILE_PART` and `SYNTHETIC`, read from the `k` element. The agent reads that one int as it reads other annotations. It decodes nothing else in the metadata, so ADR 0021's and ADR 0026's reasons against a metadata library do not apply.
- **`GeneratedBy.MULTIFILE_FACADE`.** A method of a `MULTIFILE_FACADE` class whose body only loads its arguments, calls the same-named static method of a part class, and returns is generated, recognised by bytecode shape as `DEFAULT_IMPLS` is. It keeps its probe and is never judged (ADR 0026).
- **A generated forwarder passes calls through.** A call into a `JVM_OVERLOADS`, `MULTIFILE_FACADE` or `DEFAULT_IMPLS` forwarder records edges to what the forwarder calls, as a call into `$default` or `access$` does (ADR 0024). Without this, a generated method is no graph node and a call into it resolves to nothing, so the real code behind it read as uncalled. `ENUM`, `DATA_CLASS` and `RECORD` methods are not forwarders and are unchanged.
- **Parity.** `otherlode-testkit` and the stub collector read the kind and follow forwarders the same way.

## Considered options

- **Guess from the `Kt` suffix on the server.** Rejected: `@file:JvmName` and a Java class that ends in `Kt` both break it, and the fact is in the bytecode.
- **Decode `kotlin.Metadata` with a library.** Rejected, as in ADR 0026: one int element needs no library.
- **Make generated forwarders graph nodes.** Rejected: a forwarder is not code a person wrote, so it must not root or join a cluster. It only carries the edge.

## Consequences

- A wire change: one enum and two fields, published to the Buf registry, and a binding bump in `otherlode-server`.
- The `@JvmOverloads` chain of ADR 0040 now reaches the full constructor or function through the forwarder.
- Protobuf scopes enum values to the package, and `GeneratedBy` already names `MULTIFILE_FACADE`. So the wire spells the kinds as Kotlin's own table does: `KOTLIN_KIND_NONE`, `KOTLIN_CLASS`, `FILE_FACADE`, `SYNTHETIC_CLASS`, `MULTIFILE_CLASS_FACADE` and `MULTIFILE_CLASS_PART`. A `kotlin.Metadata` with no `k` element reads as `KOTLIN_CLASS`, the element's default, and a `k` outside 1 to 5 as `KOTLIN_KIND_NONE`.
- kotlinc marks every multi-file part synthetic, and the type matcher turned away every synthetic class, so a part had no probes and the facade's forwarder was the only probe on the code. The type matcher takes a synthetic class whose kind is `MULTIFILE_CLASS_PART`, and the agent drops the synthetic clause from ByteBuddy's default ignore matcher so such a class reaches it. The loaded-class sweep cannot read the kind without loading an annotation type, so it still drops every synthetic class, and a part that reached no transformer is not reported.

## Amended on 2026-10-04: a virtual call keeps its edge to an overridable forwarder

A call into a generated forwarder records edges to what the forwarder runs in place of an edge to the forwarder. For a forwarder a subclass can override, a virtual call does not always run it: `open class Base : I` gets a stub for `I`'s default (marked `DEFAULT_IMPLS` before kotlinc 2.2, a bridge from 2.2), and `base.m()` on a `Sub` that overrides `m` runs `Sub.m`. With only the pass-through, `Sub.m` lost its one in-scope caller and read as an uncalled root. So a virtual call into a forwarder or a bridge, in its own class or another, that is neither private, static nor final, in a class that is not final, keeps its edge to the method called beside the pass-through, and a collector walks down from it to every override. Other pass-throughs (accessors, Hibernate's enhancement methods) never stand for an override and are unchanged.

A call can name a class that only inherits the method: `plain.p()` where `class Plain : Open()` and `Open` declares the stub. The edge names `Plain.p`. Under `-jvm-default=disable` the stub's code is in `I$DefaultImpls`, which is no supertype, and a collector walking up from `Plain.p` meets only `Open.p`, a generated method and no node, so the interface's code lost the caller. The agent resolves such a call at the first superclass up from the class called that declares the method, stopping at one out of scope or unreadable, and, when that declaration is a forwarder or a bridge, passes through it as for a direct call, keeping the edge to `Plain.p` so a collector can still walk down to an override. From kotlinc 2.2 the walk up already reaches the interface's own method, and the pass-through only repeats it.

The same disable mode leaves a call naming the interface itself (`d.p()` with `d: I`) with an edge to an abstract `I.p`, which is no node. So a virtual call to a method the named interface declares abstract also gets an edge to `I$DefaultImpls.p` with `I` prepended to the call's descriptor, when that class declares it with a body: the interface's own default, one of the methods the call may run. A sub-interface's default that overrides it may run instead; a collector reaches that one by looking through the implementing class's marked stub, under ADR 0024's amendment. Both agent rules here matter most for bridges, which have no probe and so no record a collector could look through: kotlinc 2.2 and later under an explicit `-jvm-default=disable`.

Under `disable`, the default up to kotlinc 2.1 and an explicit choice after, a sub-interface that inherits a default also gets a `$DefaultImpls` method that casts its receiver and forwards to the super-interface's. ADR 0026's forwarder rule marks it, and a call through it passes through.

The handler forwarder table (ADR 0035) is written only when a pass-through reaches exactly one concrete target. A pass-through whose target is an overridable stub now reaches two, the code it runs and the stub's own edge, so no entry is written: which method runs depends on the receiver, and the table records only a certain answer.
