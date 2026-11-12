---
title: Classes
description: Every state the agent can report a class in, what puts a class there, the static baseline that finds classes that never load, the class listing that shows removed code, and which generated classes the agent leaves out.
order: 50
---

A class is in scope when its name falls under a prefix in `includePackages` and under none in `excludePackages`. Exclusion wins, and the agent's own `dev.otherlode` package is never in scope. A class defined by the bootstrap or platform class loader is never instrumented, whatever the prefixes say. See [configuration options](configuration) for both options.

This page covers the whole class. For what the agent records inside a class that loaded, see [methods and branches](methods-and-branches).

## Class states

| State | What puts a class there | Where you see it |
|---|---|---|
| Instrumented | The class loaded, matched the rules, and has at least one method, optional parameter or static initialiser to probe. | Its probes reach Otherlode in the manifest. |
| Never initialised | The class loaded, has a static initialiser, and no instance ran it. | `neverInitialized()` in [the testkit](testkit-api), and Otherlode's class findings. |
| Never instantiated | The class loaded, and none of its constructors or instance methods ran in any instance. | `neverInstantiated()`, and Otherlode's class findings. |
| Skipped | The class matched the rules and the agent could not instrument it, or its manifest entries are too large to send. | A `WARNING` log line, `skippedClasses()`, and the manifest's skipped list. |
| Loaded where no transformer saw it | The class loaded, but the JVM offered it to no transformer. | One `INFO` log line, and the manifest. |
| Failed to load | The agent wove the class and the JVM never defined it. | A `WARNING` log line, `failedToLoad()`, and the manifest. |
| Failed to initialise | The JVM defined the class and either its static initialiser threw or the JVM's verifier rejected it, so the class can never run in that JVM. | The manifest, and `failedToInitialize()`. |
| Never loaded | A complete static baseline declared the class and no manifest ever mentioned it. | `neverLoaded()`, and Otherlode's class findings. |
| Nothing to probe | The class loaded and has no method, optional parameter or static initialiser the agent probes. | Nothing, except in the static baseline's unprobed list. |
| Left out | The class is synthetic or a coroutine continuation class, or a framework generated it at runtime. | Nothing. |

A class holds at most one of never loaded, never initialised and never instantiated: the first that applies, in that order. Otherlode judges these findings, and the agent sends the facts they rest on.

## Instrumented classes

When a class in scope loads, the agent weaves probes into it and registers it. The class appears in the manifest with one entry per probe, and its delta batches carry the probe counts. The agent holds a class's entries back until it has evidence the JVM defined the class. See [Failed to load](#failed-to-load).

A class has nothing to probe when it has no concrete method the agent probes (see [methods and branches](methods-and-branches)), no optional parameter, and no static initialiser of its own. A marker interface and an interface that holds only constants are typical. The live tier sends nothing about such a class. Only the static baseline's unprobed list and the class listing name it. A testkit query for one of its methods throws `UnknownProbeException`, and `neverLoaded()` does not list it.

## Never initialised and never instantiated

The agent probes a class's static initialiser, and each of its constructors, like any other method. The class findings read those probes.

A class is **never initialised** when all of these hold:

- Some instance loaded it.
- It has a static initialiser of its own in its bytecode.
- That initialiser ran in no instance.
- It did not [fail to initialise](#failed-to-initialise).

A class with no static initialiser is never judged never initialised. A Kotlin `object` has one, since its single instance is created there.

A class is **never instantiated** when all of these hold:

- Some instance loaded it.
- It is not never initialised, and it did not fail to initialise.
- Its static initialiser is not still running in every instance that loaded it.
- It has at least one constructor, and none ran in any instance.
- It has at least one method that is neither static, a constructor, nor the static initialiser, and none of those ran in any instance.

A subclass's constructor runs its superclass's, so a constructed subclass rules out its superclass too. Java deserialization and libraries such as Gson, Kryo and Objenesis make an instance without its constructor, and its instance methods then run, so the class is not judged. An interface has no constructor, and a class with only static methods is never judged. Its static methods stay listed as ordinary methods.

Inline, generated and unread-shape methods don't make a class eligible for either finding. A hit on any instance method, one of those included, still rules out never instantiated. The methods that can only run through a class with a finding are not listed on their own. A never-hit method is covered by a never-initialised class, and a never-hit constructor or instance method by a never-instantiated one. A constructor is a finding itself only as an unused overload: it never ran while another constructor of its class did.

## Skipped classes

The agent records a skipped class's name and a reason, once per class name, and sends the list in the manifest. Otherlode counts a skipped class as loaded and never as dead code. Four causes put a class here. For the first three the agent does not weave the class:

- **A missing supertype.** The agent reads the class's superclass and interfaces, and theirs in turn, as class files through the class's own loader. If the loader cannot serve one, the JVM cannot define the class with or without the agent, so the agent leaves it unwoven. A supertype defined at runtime from memory has no class file and counts as missing. Names under `java.` count as present.
- **A class loader that cannot see the agent's package.** A loader that does not delegate `dev.otherlode.bootstrap` to the boot loader cannot resolve the class a woven class refers to. The agent skips every class that loader defines and logs one `WARNING` for the loader. The reason is `its class loader cannot see dev.otherlode.bootstrap, so it is not instrumented`. See [compatibility](compatibility#class-loaders-that-do-not-delegate-to-the-boot-loader).
- **Any other failure while rewriting the class.** The reason is the failure's message.
- **Manifest entries too large to send.** The class is woven and counts, but its manifest entries alone exceed the ingest limits on encoded bytes or elements. The reason is `too large to report: its manifest entries exceed the ingest limits`, and the agent sends none of the class's hits. See [a class is too large to send](troubleshooting#a-class-is-too-large-to-send).

The first cause logs one line per class, the second one line per loader, the third a line with the stack trace, and the fourth one line per class:

```text
otherlode: com.acme.Foo cannot be defined: its supertype com.acme.Missing could not be read from its loader, so it is not instrumented
otherlode: the class loader org.jboss.modules.ModuleClassLoader cannot see dev.otherlode.bootstrap, so the agent does not instrument the classes it defines; delegate dev.otherlode.bootstrap to the boot loader, for example with -Djboss.modules.system.pkgs=dev.otherlode.bootstrap or org.osgi.framework.bootdelegation=dev.otherlode.bootstrap
otherlode: instrumentation failed for com.acme.Foo, class will run uninstrumented
otherlode: com.acme.Foo is reported as skipped: its manifest entries alone exceed the ingest limits on encoded bytes or elements, so its methods are not reported
```

A type that a field or method signature names, but that is not a supertype, does not skip a class. The class weaves, and the JVM resolves the type only if the class uses it.

## Loaded where no transformer saw it

The JVM does not call a transformer for a class it loads while the same thread is inside another transform. Such a class is defined and runs, but it has no probes and does not appear in the manifest as an instrumented or skipped class. Next to a complete static baseline that declared it, it would read as never loaded.

The agent finds these classes by sweeping every class the JVM holds. A sweep keeps the loaded classes that are in scope and that the agent never registered, skipped or found to have nothing to probe. It names each one in the manifest, and Otherlode counts it as loaded. It makes no claim about the class's methods.

The sweep runs on the first flush, on the shutdown flush and, once the dependency listing has completed, on every flush. Until the listing completes, it runs on every tenth flush after the first. With the default `flushIntervalSeconds` of 60, a class loaded with no transformer reaches a manifest within a minute once the listing has completed, and within 10 minutes before that. The sweep ignores:

- Array, primitive, hidden and synthetic classes.
- Classes on the bootstrap or platform loader.
- Names under `net.bytebuddy.`, `sun.reflect.` and `jdk.internal.reflect.`.
- Runtime-generated classes and coroutine continuation classes (see [Classes the agent leaves out](#classes-the-agent-leaves-out)).

The first sweep that finds any such class logs this line once, and later finds are silent:

```text
otherlode: 3 loaded classes reached no transformer and are reported as unreported rather than instrumented; a second agent in the chain is the usual cause
```

A testkit query about a method of such a class throws `UnknownProbeException`, and the message says that the class loaded but no transformer saw it.

## Failed to load

The agent registers a class when its transformer runs, and the JVM can still refuse to define the class afterwards, for example with a `LinkageError` for a supertype it cannot load. So the agent holds a class's probe entries out of the manifest until it has evidence the class was defined. The evidence is any one of:

- A probe count above zero.
- The class's name among the classes the JVM has loaded, checked on each flush.
- The class's loader having been garbage collected. A class on the bootstrap loader is never confirmed this way.

A class with no evidence stays held. It is **failed to load** once two flushes have missed it, and a flush counts as a miss only when the attempt to define the class has ended. A slow loader, a debugger breakpoint or a frozen process keeps the attempt open, so none of them makes a defined class look failed. The shutdown flush counts as one, so a short-lived process still gets an answer.

For each class it gives up on, the agent logs one `WARNING` and names the class in the manifest as failed to load. The name carries no reason, because the JVM raises the error after the agent's transformer has returned.

```text
otherlode: com.acme.Foo was woven and registered but the JVM never defined it; its probes are withheld for good
```

If any classes were given up on, the shutdown flush logs a total:

```text
otherlode: 2 classes were woven and registered but never confirmed defined
```

If the agent cannot read the stack that shows when a definition attempt started and ended, it logs the line below once. Classes it cannot watch this way are never reported as failed to load.

```text
otherlode: the definition attempt of a woven class could not be read from its thread's stack; such classes are never reported as failed to load
```

A class that failed to load is a deployment to fix, never dead code, even where a complete baseline declared it. If some instance loaded the class, the class is loaded, whatever another instance says. `failedToLoad()` lists the classes some manifest named as failed to load and no manifest named as probed, skipped or unreported, and `neverLoaded()` leaves them out.

## Failed to initialise

A class whose static initialiser throws is defined and never runs. The JVM does not run the initialiser again, and every later use of the class throws `NoClassDefFoundError`.

The agent puts two more probes beside the entry probe of each static initialiser the class file declares. One counts each time the initialiser returns, and one counts each time it throws. The throwing probe sits in a handler that wraps the initialiser's body and rethrows the same exception, so the class fails exactly as it does without the agent. An initialiser the agent adds itself, to an interface below Java 11 that has none, gets neither probe.

The first time the throwing probe counts, the agent names the class in the manifest's `classes_failed_to_initialize`, with the reason `STATIC_INITIALIZER_THREW` and the time. It sends the name once per class. The two probes travel with the other probes, so their counts arrive in the delta batches. See [what the agent sends](data-sent).

A class that failed to initialise is a deployment to fix, never dead code. If some instance ran the class's initialiser to the end, the class is initialised, whatever another instance says. An initialiser that has entered and neither returned nor thrown is still running. The agent names nothing for it and sends the counts as they are. The testkit's `failedToInitialize()` lists each class that failed to initialise in some instance and initialised in none, and each class that extends or implements a type whose failure keeps it from initialising. It lists only classes a manifest named as probed, skipped or unreported; see [the testkit API](testkit-api#classes-that-failed-to-initialise).

### Rejected by the verifier

The JVM verifies a class when it links it, after it has defined it. A class the verifier rejects stays defined, throws a `VerifyError` on every later use, and fires no probe.

The agent weaves the two constructors of `java.lang.VerifyError`. Each construction hands its message to a fixed-size queue and does nothing else. On each flush, the export thread reads the class name out of every message. It knows the message formats of JDK 17, 21 and 25, and of the older verifier, which checks classes below version 50 and version 50 classes the newer one rejects. The agent names the class in `classes_failed_to_initialize` with the reason `VERIFIER_REJECTED`, once per class. It sends every name it reads, whether or not the class is under your include rules. It leaves out a name when its own retransformation of that class failed.

The agent counts a message it cannot read and never guesses at it. `unplaced_verification_failures`, in every manifest, is the run's count of them. The JVM builds the same message again each time something uses a rejected class, so the agent skips a message equal to one it kept recently. It holds 8,192 messages between flushes, and each message it had no room for counts as unplaced too. Once that count is above zero, the run makes no claim from a zero count.

A run without the hook makes no such claim either. If the agent cannot install it, it logs one `WARNING` and every manifest of the run says `verifier_rejections_watched` is false.

```text
otherlode: classes the JVM's verifier rejects will not be named on this JVM, so a run's zero counts say nothing about them: <reason>
```

## Never loaded

A class that never loads sends nothing. The **static baseline** is an inventory of the classes under `includePackages`, read from the class files without loading them. A class the baseline declares and that no manifest from any instance ever mentions is **never loaded**.

### Enable the baseline

The baseline is off by default, since a classpath walk takes time in proportion to the size of the classpath. Turn it on with `staticBaselineEnabled=true` (see [configuration options](configuration)). Without it, the agent reports no never-loaded classes, and `neverLoaded()` in the testkit throws until a complete scan has arrived.

The scan runs once per process, on a background thread that does not hold up startup. It sends its result as soon as it finishes, without waiting for a flush. It does not run again, so a jar that a plugin loader adds later is not captured until the JVM restarts.

### What the scan reads

The scan reads the classpath as the JVM's system class loader does. It walks each entry of `java.class.path`: a directory, or any other file as a jar, whatever its extension. It also follows the `Class-Path` attribute of every jar's manifest to further jars and directories, which is how `java -jar app.jar` finds its libraries. A `Class-Path` resolves against the real path of a jar on `java.class.path`, with every symbolic link in it followed, and against the path as written for a jar that another jar names.

The scan leaves a file out, with a warning in the log, when the JVM would not load from it:

- A file that cannot be opened as a jar.
- A jar with a `Class-Path` token that is not a valid URL. The files that jar names are left out with it.
- A jar whose manifest cannot be read, when the manifest text holds `Class-Path:`, or holds `Multi-Release: true` and its main section cannot be read, and a jar whose manifest is larger than the JVM reads, 16 MB by default. The files that jar names are left out with it.

The scan asks the JVM it runs on in these cases, so a rule that differs between Java versions follows the version you run. On Java 17, a jar that holds `META-INF/INDEX.LIST` is kept, and the JVM then loads classes from the jars its index names, which the scan does not read.

A jar whose manifest cannot be read and that the JVM keeps is read as the JVM reads it. The JVM defines only its classes in the unnamed package, so the scan declares those and no others from it, and none under `BOOT-INF/classes/` or `WEB-INF/classes/`. For a class of a named package in that jar, the scan declares neither that copy nor a later one, since the JVM loads neither.

When two entries hold a class of the same name, the baseline keeps the copy the JVM would load, the first in the classpath's search order. The jars a `Class-Path` names are searched right after the jar that names them. The `BOOT-INF/classes/` and `WEB-INF/classes/` folders are searched after every other entry. In a multi-release jar, the scan declares the copy the JVM loads: the one under the highest `META-INF/versions/N/` at or below the Java version the application runs on, else the copy at the root of the jar. A class that such a jar holds only under `META-INF/versions/` is declared from there.

Inside a jar, the scan reads:

- Classes at the root of the jar.
- Classes under `BOOT-INF/classes/` and `WEB-INF/classes/`, where a Spring Boot executable jar and a WAR keep the application's own code.

A directory that holds an unpacked Spring Boot application or WAR is read the same way: its own classes are named from inside `BOOT-INF/classes/` or `WEB-INF/classes/`, and searched after every other entry.

The scan does not open jars nested under `BOOT-INF/lib/` or `WEB-INF/lib/`, or the jars in an unpacked application's library folders. If `includePackages` covers a library that you ship as a nested jar, its classes are instrumented when they load but never declared, so they can never be reported as never loaded. [Dependencies](dependencies) covers nested jars.

The scan applies the same rules as the live tier to each class: `includePackages`, `excludePackages`, and the left-out classes below.

### What the scan reports

Each class the scan finds in scope lands in one of three lists:

| List | Contents |
|---|---|
| Declared | A class with at least one method or static initialiser to probe, with its methods. |
| Unprobed | A class with nothing to probe, such as an interface with only abstract methods or an annotation type. |
| Unreadable | A class file the scan could not read or resolve, with the reason. The scan logs nothing about it. |

Only a declared class can be never loaded.

Otherlode calls a declared class never loaded only when it holds the complete scan. The agent sends the scan in chunks of about 20,000 entries, and each chunk stays under 8 MiB and under 180,000 elements (see [chunk caps](data-sent#chunk-caps)). Each chunk says its position out of the total. The agent sends the chunks in order and stops at the first failure. It sends the rest one chunk per flush, after each flush whose payloads are stored, and logs these lines on the way:

```text
otherlode: failed to send static baseline chunk 2 of 3; it and the chunks after it are sent again after a flush whose payloads are stored
otherlode: the static baseline was stored after a retry
otherlode: static baseline chunk 1 of 3 was refused with status 400, which resending the same bytes cannot change; the scan is not sent again
```

A class is never split across chunks. If one declared class is too large for a chunk on its own, over 8 MiB or over 180,000 elements, the agent sends no chunk of the scan and logs one `WARNING` that names the class and the bound, never a string constant from it. The run then has no complete scan, so Otherlode makes no never-loaded claim for it.

```text
otherlode: the static baseline is not sent: com.acme.Huge alone exceeds the limit of 8 MiB on encoded bytes for one chunk, and a scan without it would read as complete
```

In the testkit, `neverLoaded()` also leaves out a declared class whose methods are all inline, generated or unread shapes.

### What the scan cannot see

The scan finds classes only in the places above. It cannot find a class whose location another system decides at run time. If you start Tomcat with `java -cp bootstrap.jar:tomcat-juli.jar org.apache.catalina.startup.Bootstrap start`, nothing about your application is on `java.class.path`, and the WAR that Tomcat deploys later is not in the scan. A plugin loader that scans a directory of its own has the same effect.

Classes in those places are instrumented normally when they load. What they lack is a declaration, so a class there that never loads is never called never loaded.

The agent logs the gap when it sees it. If a class registers when it loads and is in none of the scan's lists, the scan missed it for this process. The agent logs this line once per class:

```text
otherlode: com.acme.Foo registered dynamically but was not in the static baseline computed at startup for this process; the static scan cannot see classes a server or plugin loader finds at run time, such as a deployed WAR, so this deployment may have such a blind spot
```

Classes that register before the scan finishes are checked when it ends, with a similar line that begins `otherlode: com.acme.Foo registered dynamically before the static baseline scan finished and was not found by it`. The warning appears only when `staticBaselineEnabled` is on. The classes the agent leaves out never trigger it.

## The class listing

A class missing from a run's probes may be deleted, or it may not have loaded yet. The **class listing** tells the two apart. It is on by default: once per process, the agent lists the names of the classes the build holds.

The listing holds names only. It reads no class file, so it says which classes exist and not what they contain. It lists every class file whose name falls under `includePackages` and under no prefix in `excludePackages`. Like the scan, it leaves out `module-info` and `package-info`. In a multi-release jar, a class under `META-INF/versions/N/` is listed by its own name. The rules that need a class's bytes, such as the synthetic-class rule in [Classes the agent leaves out](#classes-the-agent-leaves-out), do not apply to it. The listing carries the include and exclude rules it applied, so Otherlode can tell a class outside them from a class that is gone.

It reads the classpath the way the JVM's system class loader does:

- Each entry of `java.class.path`, read through its real path with every symbolic link in it followed, and the jars that a manifest `Class-Path` names. A directory is walked, and any other file is read as a jar, whatever its extension.
- In a Spring Boot executable jar or WAR, the classes under `BOOT-INF/classes/` or `WEB-INF/classes/`, and the classes in each nested jar under `BOOT-INF/lib/`, or `WEB-INF/lib/` and `WEB-INF/lib-provided/`. `includePackages` filters the nested jars like any other entry, so a multi-module application's own nested jars are listed.
- A directory that holds an unpacked Spring Boot application, as a container image built by Spring Boot's build plugins or a buildpack runs it, the same way: its `BOOT-INF/classes/`, every jar in its library folder, and every jar or folder its classpath index names.

Like the scan, the listing cannot see a class whose location another system decides at run time: a WAR that an application server deploys, an OSGi bundle, a plugin folder, or the extra folders Spring Boot's `PropertiesLauncher` reads from `loader.path`. The first time a class under `includePackages` loads from a jar or folder the listing did not read, the run's listing is blind. From then on every manifest the run sends carries `class_listing_blind`, and the agent logs one `INFO` line that names the location. While that run is live, Otherlode claims no class removed in its environment. A class that loads with no code source sets the mark when the listing does not name it, which includes the classes some code generators define, such as Jackson Afterburner's accessors. A proxy that Spring, Hibernate or ByteBuddy generates does not set it, and nor does a class of the JDK.

The agent sees a root only when a class loads from it. A plugin folder whose classes never load stays unseen, and a class that exists only there can still read as removed. A run whose classpath the listing could not read whole is never marked.

The agent sends a listing only when it read the whole classpath. If a directory, a jar or a nested jar that exists cannot be opened or read, the agent sends no listing for that process and logs one warning that names the file and the cause. A classpath entry that does not exist is skipped, as the JVM skips it. A jar on the classpath whose `Class-Path` holds a token that is not a valid URL is listed by its own classes only, and the jars it names are not read. Two cases with such a token give no listing: on Java 17, a jar that also holds `META-INF/INDEX.LIST`, and a library jar of an unpacked Spring Boot application.

The agent also sends no listing when:

- `java.class.path` is empty or has an empty element, which the JVM reads as the working directory.
- The JVM runs from a module path, launches a linked image's main module, or upgrades or patches modules.
- The JVM has no `java.management` module, which the agent needs to see `--patch-module`.
- An unpacked Spring Boot application's classpath index is malformed.

The agent sends the listing in chunks of at most 20,000 names, in order, and each chunk says its position out of the total. It sends chunks again like the baseline's: after a flush whose payloads are stored, one per flush, and a `400` or `422` answer drops the rest. Set `classListingEnabled=false` to switch it off. See [configuration options](configuration) and [what the agent sends](data-sent#class-listing-chunk).

## Classes the agent leaves out

The agent never instruments or declares these classes, and the sweep ignores them too:

- **Spring CGLIB classes.** A name containing `$$SpringCGLIB$$` (Spring 6 and 7) or `BySpringCGLIB$$` (Spring 5.3), including the `FastClass` helpers.
- **Hibernate classes.** A name with a `$`-separated part that is `HibernateProxy`, `HibernateBasicProxy`, `HibernateInstantiator`, or an access optimiser (`HibernateAccessOptimizer`, with its bridge). The match is on a whole part, so a class you wrote called `Util$HibernateProxyUnwrapper` is kept.
- **ByteBuddy and Mockito classes.** A part `ByteBuddy` or `MockitoMock` followed by a random-looking tail of eight or fifteen letters and digits. If the JVM sets `net.bytebuddy.naming` to `fixed` or `caller`, a name that ends in a `ByteBuddy` part also matches.
- **Javassist proxies.** A name containing `_$$_jvst`.
- **JDK dynamic proxies** in your packages. The simple name is `$Proxy` and a number.
- **Coroutine continuation classes.** The class kotlinc writes for each suspend function, whose direct superclass is `ContinuationImpl` or `RestrictedContinuationImpl`. A suspend lambda extends `SuspendLambda` instead and holds your code, so it is kept.
- **Synthetic classes.** Any class with the JVM's synthetic flag, such as the class kotlinc writes for a function reference. One exception: a part of a Kotlin multi-file facade is synthetic, holds your code, and is kept.

The [class listing](#the-class-listing) applies none of these rules, so it names any of these classes that has a class file.

The agent recognises a generated class only by the rules above, so a proxy that its generator names another way, and that has no synthetic flag, is instrumented. A proxy reaches the method it overrides by calling `super`, so the class it proxies carries the counts.

## Log lines

Every message starts with `otherlode:`. In the JVM's default format, the logger puts the level in front, as in `WARNING: otherlode:`, below a line with the time and the class that logged it.

| Level | Line (abridged) | Meaning |
|---|---|---|
| `WARNING` | `<class> cannot be defined: its supertype <type> could not be read from its loader, so it is not instrumented` | The class is skipped. |
| `WARNING` | `the class loader <loader> cannot see dev.otherlode.bootstrap, so the agent does not instrument the classes it defines; ...` | Every class of the loader is skipped. The line is logged once per loader. |
| `WARNING` | `instrumentation failed for <class>, class will run uninstrumented` | The class is skipped, and the stack trace follows. |
| `WARNING` | `<class> is reported as skipped: its manifest entries alone exceed the ingest limits ...` | A loaded class's manifest entries are too large to send, so it is reported as skipped and its hits are not sent. |
| `INFO` | `<n> loaded classes reached no transformer and are reported as unreported rather than instrumented; ...` | The first sweep found classes no transformer saw. |
| `WARNING` | `<class> was woven and registered but the JVM never defined it; its probes are withheld for good` | The class failed to load. |
| `WARNING` | `<n> classes were woven and registered but never confirmed defined` | The shutdown total of classes that failed to load. |
| `WARNING` | `<class> declared <n> endpoints while being woven but the JVM never defined it; its endpoints are withheld for good` | A JAX-RS resource class failed to load, and its routes are left out. |
| `WARNING` | `<n> classes declared endpoints while being woven but were never confirmed defined` | The shutdown total of such resource classes. |
| `WARNING` | `the definition attempt of a woven class could not be read from its thread's stack; ...` | Some classes cannot be reported as failed to load. |
| `WARNING` | `classes the JVM's verifier rejects will not be named on this JVM, so a run's zero counts say nothing about them: <reason>` | The hook on `VerifyError` did not install. |
| `WARNING` | `reading the verifier's rejections failed, so this run's zero counts are no longer evidence` | Reading what the hook collected failed on one flush. The run counts one unplaced failure and makes no claim from a zero count. |
| `WARNING` | `<class> registered dynamically but was not in the static baseline computed at startup ...` | The static scan has a blind spot in this deployment. |
| `WARNING` | `the static baseline is not sent: <class> alone exceeds <bound> for one chunk, ...` | One class is too large for a chunk, so the agent sends no chunk of the scan. |
| `WARNING` | `failed to send static baseline chunk <i> of <n>; ...` | A chunk will be sent again. |
| `WARNING` | `static baseline chunk <i> of <n> was refused with status <code>, ...` | The receiver refused a chunk with a `400` or `422`. The agent does not resend the scan. |
| `INFO` | `the static baseline was stored after a retry` | A failed chunk got through. |
| `WARNING` | `could not read <file> for the class listing, so no class listing is sent: <cause>` | A directory, jar or nested jar could not be read, so this process sends no class listing. |
| `WARNING` | `java.class.path is empty or has an empty element, which the JVM reads as the working directory, so no class listing is sent` | Name the classpath, or remove the empty element, such as a leading or trailing `:`, to get a listing. |
| `WARNING` | `this JVM runs from a module path or a linked image, which the class listing does not read, so no class listing is sent` | The application runs from the module path or launches a `jlink` image's main module. No listing is sent, so Otherlode removes no code for this process. |
| `WARNING` | `this JVM upgrades or patches modules, which the class listing does not read, so no class listing is sent` | The JVM runs with `--upgrade-module-path` or `--patch-module`. No listing is sent, so Otherlode removes no code for this process. |
| `WARNING` | `this JVM has no java.management module, so the agent cannot tell whether it patches modules, so no class listing is sent` | A linked image left out `java.management`, which the agent needs to see `--patch-module`. No listing is sent, so Otherlode removes no code for this process. Add the module to the image to get a listing. |
| `WARNING` | `the class listing failed, so no class listing is sent` | An unexpected error stopped the listing. The stack trace follows. |
| `INFO` | `<class> loaded from <location>, which the class listing did not read, so Otherlode removes no class from this environment while this run is live` | A class under `includePackages` loaded from a root the listing did not read. [The class listing missed a root](troubleshooting#the-class-listing-missed-a-root) lists the other forms of this line. |
| `WARNING` | `failed to send class listing chunk <i> of <n>; ...` | A chunk will be sent again. |
| `WARNING` | `class listing chunk <i> of <n> was refused with status <code>, ...` | The receiver refused a chunk with a `400` or `422`. The agent does not resend the listing. |
| `INFO` | `the class listing was stored after a retry` | A failed chunk got through. |
