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
| Instrumented | The class loaded, matched the rules, and has at least one method, default-argument site or static initialiser to probe. | Its probes reach the collector in the manifest. |
| Never initialised | The class loaded, has a static initialiser, and no instance ran it. | `neverInitialized()` in [the testkit](testkit-api), and Otherlode's class findings. |
| Never instantiated | The class loaded, and none of its constructors ran in any instance. | `neverInstantiated()`, and Otherlode's class findings. |
| Skipped | The class matched the rules and the agent could not instrument it, or its manifest entries are too large to send. | A `WARNING` log line, `skippedClasses()`, and the manifest's skipped list. |
| Loaded where no transformer saw it | The class loaded, but the JVM offered it to no transformer. | One `INFO` log line, and the manifest. |
| Failed to load | The agent wove the class and the JVM never defined it. | A `WARNING` log line, `failedToLoad()`, and the manifest. |
| Failed to initialise | The JVM defined the class and either its static initialiser threw or the JVM's verifier rejected it, so the class can never run in that JVM. | The manifest, and `failedToInitialize()`. |
| Never loaded | A complete static baseline declared the class and no manifest ever mentioned it. | `neverLoaded()`, and Otherlode's class findings. |
| Nothing to probe | The class loaded and has no method, default-argument site or static initialiser the agent probes. | Nothing, except in the static baseline's unprobed list. |
| Left out | The class is synthetic, or a framework generated it at runtime. | Nothing. |

A class holds at most one of never loaded, never initialised and never instantiated: the first that applies, in that order. Otherlode judges these findings. The agent sends the facts they rest on.

## Instrumented classes

When a class in scope loads, the agent weaves probes into it and registers it. The class appears in the manifest with one entry per probe, and its delta batches carry the probe counts. The agent holds a class's entries back until it has evidence the JVM defined the class. See [Failed to load](#failed-to-load).

A class has nothing to probe when it has no concrete method the agent probes (see [methods and branches](methods-and-branches)), no default-argument site, and no static initialiser of its own. A marker interface and a constants holder are typical. The agent sends nothing about such a class. A testkit query for one of its methods throws `UnknownProbeException`, and `neverLoaded()` does not list it.

## Never initialised and never instantiated

The agent probes a class's static initialiser, and each of its constructors, like any other method. The class findings read those probes.

A class is **never initialised** when all of these hold:

- Some instance loaded it.
- It has a static initialiser of its own in its bytecode.
- That initialiser ran in no instance.

Nothing used the class's statics, and nothing created an instance. A class with no static initialiser is never judged never initialised, so it can be loaded or never loaded, and nothing in between. A Kotlin `object` has one, since its single instance is created there.

A class is **never instantiated** when all of these hold:

- Some instance loaded it.
- It is not never initialised.
- It has at least one constructor, and none ran in any instance.
- It has at least one method that is neither static, a constructor, nor the static initialiser.

No instance of the class or of a subclass ever existed, since a subclass's constructor runs its superclass's. An interface has no constructor, and a class with only static methods is never judged. The class's static methods can still run, so they stay listed as ordinary methods.

Both findings leave out the class's inline, generated and unread-shape methods when judging, because the agent makes no never-hit claim about those. The methods that can only run through a class with a finding are not listed on their own. A never-hit method is covered by a never-initialised class, and a never-hit constructor or instance method by a never-instantiated one. A constructor is a finding itself only as an unused overload: it never ran while another constructor of its class did.

## Skipped classes

The agent skips a class that matched the rules and that it could not instrument. The class runs unchanged. The agent records the class name and a reason, once per class name, and sends the list in the manifest, so Otherlode counts the class as loaded and never as dead code.

Three causes put a class here:

- **A missing supertype.** The agent reads the class's superclass and interfaces, and theirs in turn, as class files through the class's own loader. A supertype the loader cannot serve as a class file makes the class one the JVM cannot define with or without the agent, so the agent refuses it. A supertype defined at runtime from memory has no class file and counts as missing. Names under `java.` count as present. Weaving such a class would publish probes for a class that never defines.
- **A class loader that cannot see the agent's package.** A loader that does not delegate `dev.otherlode.bootstrap` to the boot loader cannot resolve the class a woven class refers to. The agent skips every class that loader defines and logs one `WARNING` for the loader. The reason is `its class loader cannot see dev.otherlode.bootstrap, so it is not instrumented`. See [compatibility](compatibility#class-loaders-that-do-not-delegate-to-the-boot-loader).
- **Any other failure while rewriting the class.** The reason is the failure's message.

The first cause logs one line per class, the second one line per loader, and the third a line with the stack trace:

```text
otherlode: com.acme.Foo cannot be defined: its supertype com.acme.Missing could not be read from its loader, so it is not instrumented
otherlode: the class loader org.jboss.modules.ModuleClassLoader cannot see dev.otherlode.bootstrap, so the agent does not instrument the classes it defines; delegate dev.otherlode.bootstrap to the boot loader, for example with -Djboss.modules.system.pkgs=dev.otherlode.bootstrap or org.osgi.framework.bootdelegation=dev.otherlode.bootstrap
otherlode: instrumentation failed for com.acme.Foo, class will run uninstrumented
```

A type that a field or method signature names, but that is not a supertype, does not skip a class. The class weaves, and the JVM resolves the type only if the class uses it.

## Loaded where no transformer saw it

The JVM does not call a transformer for a class it loads while the same thread is inside another transform. The agent never sees such a class, and neither does any other agent. It is defined and runs, but it has no probes and does not appear in the manifest as an instrumented or skipped class. Next to a complete static baseline that declared it, the class would read as never loaded, though it ran.

The agent finds these classes by sweeping every class the JVM holds. A sweep keeps the loaded classes that are in scope and that the agent never registered, skipped or found to have nothing to probe. It names each one in the manifest, and Otherlode counts it as loaded. It makes no claim about the class's methods.

The sweep runs every tenth flush and on the shutdown flush. With the default `flushIntervalSeconds` of 60, that is about every ten minutes. The sweep ignores:

- Array, primitive, hidden and synthetic classes.
- Classes on the bootstrap or platform loader.
- Names under `net.bytebuddy.`, `sun.reflect.` and `jdk.internal.reflect.`.
- Runtime-generated classes and coroutine continuation classes (see [Classes the agent leaves out](#classes-the-agent-leaves-out)).

The first sweep that finds any such class logs this line once:

```text
otherlode: 3 loaded classes reached no transformer and are reported as unreported rather than instrumented; a second agent in the chain is the usual cause
```

Later finds are silent. A testkit query about a method of such a class throws `UnknownProbeException`, and the message says that the class loaded but no transformer saw it.

## Failed to load

The agent registers a class when its transformer runs. The JVM can still refuse to define the class afterwards, for example when it raises a `LinkageError` for a supertype it cannot load. A class like that would sit in the manifest at zero hits and read as dead code. So the agent holds a class's probe entries out of the manifest until it has evidence the class was defined. The evidence is any one of:

- A probe count above zero.
- The class's name appearing among the classes the JVM has loaded, checked on each flush.
- The class's loader having been garbage collected. A loader created, used and collected between two flushes is ordinary for a script engine or a per-test loader, and withholding the class there would lose a class that ran. A class on the bootstrap loader is never confirmed this way.

A class with no evidence stays held. It is **failed to load** once two sweeps have missed it, and a sweep counts as a miss only when the attempt to define the class has ended. A slow loader, a debugger breakpoint or a frozen process keeps the attempt open, so none of them makes a defined class look failed. The shutdown flush counts as one sweep, so a short-lived process still gets an answer.

For each class it gives up on, the agent logs one `WARNING` and names the class in the manifest as failed to load. The name carries no reason, because the error is raised after the agent's transformer has returned.

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

A class that failed to load is a deployment to fix, never dead code. Otherlode reads it that way even where a complete baseline declared the class. If some instance loaded the class, the class is loaded, whatever another instance says. `failedToLoad()` lists the classes some manifest named and no instance loaded, and `neverLoaded()` leaves them out.

## Failed to initialise

A class whose static initialiser throws is defined, and it still never runs. The JVM does not run the initialiser again, and every later use of the class throws `NoClassDefFoundError`. Such a class would read as dead code: its static initialiser's entry probe counted, and every method the initialiser did not reach sits at zero.

The agent puts two more probes beside the entry probe of each static initialiser the class file declares. One counts each time the initialiser returns, and one counts each time it throws. The throwing probe sits in a handler that wraps the initialiser's body and rethrows the same exception, so the class fails exactly as it does without the agent. An initialiser the agent adds itself, to an interface below Java 11 that has none, gets neither probe.

The first time the throwing probe counts, the agent names the class in the manifest's `classes_failed_to_initialize`, with the reason `STATIC_INITIALIZER_THREW` and the time. It sends the name once per class. The two probes travel with the other probes, so their counts arrive in the delta batches. See [what the agent sends](data-sent).

A class that failed to initialise is a deployment to fix, never dead code. If some instance ran the class's initialiser to the end, the class is initialised, whatever another instance says. An initialiser that has entered and neither returned nor thrown is still running. The agent names nothing for it and sends the counts as they are. The testkit's `failedToInitialize()` lists the classes that failed to initialise; see [the testkit API](testkit-api#classes-that-failed-to-initialise).

### Rejected by the verifier

The JVM verifies a class when it links it, after it has defined it. A class the verifier rejects stays defined, throws a `VerifyError` on every later use, and fires no probe, so it would read as dead code too.

The agent weaves the two constructors of `java.lang.VerifyError`. Each construction hands its message to a fixed-size queue and does nothing else. On each flush, the export thread reads the class name out of every message. It knows the message formats of JDK 17, 21 and 25, and of the older verifier, which checks classes below version 50 and version 50 classes the newer one rejects. The agent names the class in `classes_failed_to_initialize` with the reason `VERIFIER_REJECTED`, once per class. It sends every name it reads, whether or not the class is under your include rules, because a rejected supertype outside them stops the classes you own that extend it from linking. It leaves out a name when its own retransformation of that class failed, since that class keeps running.

A message the agent cannot read is counted and never guessed at. `unplaced_verification_failures`, in every manifest, is the run's count of them. The JVM checks a rejected class again, and builds the same message again, each time something uses it, so the agent skips a message equal to one it kept recently. It holds 8,192 messages between flushes, and each message it had no room for counts there too. Once that count is above zero, the run makes no claim from a zero count, because any unread rejection may have been a class that now reads as dead.

A run without the hook makes no such claim either. If the agent cannot install it, it logs one `WARNING` and every manifest of the run says `verifier_rejections_watched` is false.

```text
otherlode: classes the JVM's verifier rejects will not be named on this JVM, so a run's zero counts say nothing about them: <reason>
```

## Never loaded

A class that never loads sends nothing, so on the wire it looks the same as a class that does not exist, was renamed, or is outside `includePackages`. The **static baseline** closes that gap: an inventory of the classes under `includePackages`, read from the class files without loading them. A class the baseline declares and that no manifest from the same instance ever mentions is **never loaded**. No instance of the service ever loaded it.

### Enable the baseline

The baseline is off by default, because a classpath walk costs time in proportion to the size of the classpath. Turn it on with `staticBaselineEnabled=true` (see [configuration options](configuration)). Without it, the agent reports no never-loaded classes, and `neverLoaded()` in the testkit throws until a complete scan has arrived.

The scan runs once per process, on a background thread that does not hold up startup. It sends its result as soon as it finishes, independently of the flush schedule. The scan does not run again, so a jar that a plugin loader adds later is not captured until the JVM restarts.

### What the scan reads

The scan walks each entry of `java.class.path` that is a directory, a `.jar` file or a `.zip` file. It also follows the `Class-Path` attribute of every jar's manifest to further jars and directories, resolved as the JDK resolves them, which is how `java -jar app.jar` finds its libraries. A jar whose manifest cannot be read is left out, with a warning in the log.

When two entries hold a class of the same name, the baseline keeps the copy the JVM would load, the first in the classpath's search order. The jars a `Class-Path` names are searched right after the jar that names them. The `BOOT-INF/classes/` and `WEB-INF/classes/` folders are searched after every other entry, as Spring Boot's launcher searches them. A class that a multi-release jar holds only under `META-INF/versions/` keeps its name from a later copy, but is not declared.

Inside a jar, the scan reads:

- Classes at the root of the jar.
- Classes under `BOOT-INF/classes/` and `WEB-INF/classes/`, which is where a Spring Boot executable jar and a WAR keep the application's own code.

It does not open jars nested under `BOOT-INF/lib/` or `WEB-INF/lib/`. If `includePackages` covers a library that you ship as a nested jar, its classes are instrumented when they load but never declared, so they can never be reported as never loaded. [Dependencies](dependencies) explains how the agent treats nested jars.

The scan applies the same rules as the live tier to each class: `includePackages`, `excludePackages`, and the left-out classes below. A class the live tier would skip is not declared.

### What the scan reports

Each class the scan finds in scope lands in one of three lists:

| List | Contents |
|---|---|
| Declared | A class with at least one method or static initialiser to probe, with its methods. |
| Unprobed | A class with nothing to probe, such as an interface with only abstract methods or an annotation type. |
| Unreadable | A class file the scan could not read or resolve, with the reason. The scan logs nothing about it. |

Only a declared class can be never loaded. The live tier never registers an unprobed class, so declaring one would make Otherlode call a heavily used marker interface never loaded. An unreadable class never silently drops out of the inventory, since it is on the list.

Otherlode calls a declared class never loaded only when it holds the complete scan. The agent sends the scan in chunks of about 20,000 entries, and each chunk stays under 8 MiB and under 180,000 elements (see [chunk caps](data-sent#chunk-caps)). Each chunk says its position out of the total. The agent sends the chunks in order and stops at the first failure. It sends the rest after the next flush that the collector confirms, and logs these lines on the way:

```text
otherlode: failed to send static baseline chunk 2 of 3; it and the chunks after it are sent again after a flush the collector confirms
otherlode: the static baseline reached the collector after a retry
otherlode: the collector refused static baseline chunk 1 of 3 with status 400, which resending the same bytes cannot change; the scan is not sent again
```

A class is never split across chunks. If one declared class is too large for a chunk on its own, over 8 MiB or over 180,000 elements, the agent sends no chunk of the scan and logs one `WARNING` that names the class and the bound, never a string constant from it:

```text
otherlode: the static baseline is not sent: com.acme.Huge alone exceeds the 8 MiB limit on encoded bytes for one chunk, and a scan without it would read as complete
```

A scan without the class would be complete and wrong. Otherlode would miss the class's call edges and creations, and another class could read as never constructed. The run has no complete scan, so Otherlode makes no never-loaded claim for it.

In the testkit, `neverLoaded()` also leaves out a declared class whose methods are all inline, generated or unread shapes. Kotlin callers never call an inline method, and the compiler regenerates generated ones, so such a class never loading is no evidence it is dead.

### What the scan cannot see

The scan can only find classes in places it knows to look. It cannot find a class whose location another system decides at run time. If you start Tomcat with `java -cp bootstrap.jar:tomcat-juli.jar org.apache.catalina.startup.Bootstrap start`, nothing about your application is on `java.class.path`. Tomcat reads `server.xml` after the agent's scan has finished, finds `webapps/shop.war`, and builds a class loader for it. A plugin loader that scans a directory at its own discretion has the same effect.

Classes in those places are instrumented normally when they load, since the JVM tells the agent about every class any loader defines. What they lack is a declaration. A class in the WAR that never loads is not in the baseline, and so Otherlode never calls it never loaded.

The agent warns you when it detects the gap. If a class registers when it loads and is in none of the scan's lists, the scan missed it for this process. The agent logs this line once per class:

```text
otherlode: com.acme.Foo registered dynamically but was not in the static baseline computed at startup for this process; the static scan cannot see classes a server or plugin loader finds at run time, such as a deployed WAR, so this deployment may have such a blind spot
```

Classes that register before the scan finishes are checked when it ends, with a similar line that begins `otherlode: com.acme.Foo registered dynamically before the static baseline scan finished and was not found by it`. This matters at startup, which is when an application server deploys its WAR. The warning appears only when `staticBaselineEnabled` is on. The classes the agent leaves out never trigger it.

## The class listing

A class that is missing from a run's probes may be deleted, or it may not have loaded yet. The static baseline can name every class, but it is off by default. The **class listing** is on by default and cheaper: once per process, the agent lists the names of the classes the build holds. Otherlode uses it to tell removed code from code that has not loaded.

The listing holds names only. It reads no class file, so it says which classes exist and not what they contain. It lists every class file whose name falls under `includePackages` and under no prefix in `excludePackages`. Like the scan, it leaves out `module-info` and `package-info`. In a multi-release jar, a class under `META-INF/versions/N/` is listed by its own name, since the JVM can load a class from there. The rules that need a class's bytes, such as the synthetic-class rule in [Classes the agent leaves out](#classes-the-agent-leaves-out), do not apply to it. The listing carries the include and exclude rules it applied, so Otherlode can tell a class outside them from a class that is gone.

It reads the classpath the way the JVM's system class loader does:

- Each entry of `java.class.path`, read through its real path with every symbolic link in it followed, and the jars that a manifest `Class-Path` names. A directory is walked, and any other file is read as a jar, whatever its extension.
- In a Spring Boot executable jar or WAR, the classes under `BOOT-INF/classes/` or `WEB-INF/classes/`, and the classes in each nested jar under `BOOT-INF/lib/`, or `WEB-INF/lib/` and `WEB-INF/lib-provided/`. A multi-module application keeps its own classes in nested jars, and `includePackages` filters them like any other.
- A directory that holds an unpacked Spring Boot application, as a container image built by Spring Boot's build plugins or a buildpack runs it, the same way: its `BOOT-INF/classes/`, every jar in its library folder, and every jar or folder its classpath index names.

Like the scan, the listing cannot see a class whose location another system decides at run time: a WAR that an application server deploys, an OSGi bundle, a plugin folder, or the extra folders Spring Boot's `PropertiesLauncher` reads from `loader.path`. Otherlode accepts a class's absence only when an earlier listing named that class, so a place the agent never reads cannot turn into a false removal.

The agent sends a listing only when it read the whole classpath. If a directory, a jar or a nested jar that exists cannot be opened or read, the agent sends no listing for that process and logs one warning that names the file and the cause. A listing with a hole would read as proof that the missing classes are gone. A classpath entry that does not exist is skipped, as the JVM skips it. The agent also sends no listing when `java.class.path` is empty or has an empty element, which the JVM reads as the working directory, or when the JVM runs from a module path or a linked image, which the listing does not read. An unpacked Spring Boot application whose classpath index is malformed also gives no listing. Spring Boot does not launch from one either.

The agent sends the listing in chunks of at most 20,000 names, in order, and each chunk says its position out of the total. It sends chunks again like the baseline's: after a flush the collector confirmed, one per flush, and a `400` or `422` answer drops the rest. Set `classListingEnabled=false` to switch it off. See [configuration options](configuration) and [what the agent sends](data-sent#class-listing-chunk).

## Classes the agent leaves out

The agent never instruments, declares or reports these classes, and the sweep ignores them too:

- **Spring CGLIB classes.** A name containing `$$SpringCGLIB$$` (Spring 6 and 7) or `BySpringCGLIB$$` (Spring 5.3), including the `FastClass` helpers.
- **Hibernate classes.** A name with a `$`-separated part that is `HibernateProxy`, `HibernateBasicProxy`, `HibernateInstantiator`, or an access optimiser (`HibernateAccessOptimizer`, with its bridge). The match is on a whole part, so a class you wrote called `Util$HibernateProxyUnwrapper` is kept.
- **ByteBuddy and Mockito classes.** A part `ByteBuddy` or `MockitoMock` followed by a random-looking tail of eight or fifteen letters and digits. If the JVM sets `net.bytebuddy.naming` to `fixed` or `caller`, a name that ends in a `ByteBuddy` part also matches.
- **Javassist proxies.** A name containing `_$$_jvst`.
- **JDK dynamic proxies** in your packages. The simple name is `$Proxy` and a number.
- **Coroutine continuation classes.** The class kotlinc writes for each suspend function, whose direct superclass is `ContinuationImpl` or `RestrictedContinuationImpl`. A suspend lambda extends `SuspendLambda` instead and holds your code, so it is kept.
- **Synthetic classes.** Any class with the JVM's synthetic flag, such as the class kotlinc writes for a function reference. One exception: a part of a Kotlin multi-file facade is synthetic, holds your code, and is kept.

The rule is the generator's naming, since a proxy lands in your package under a name built from the class it proxies. A name test is the only reliable one, because the other signs a generated class shows, such as missing line numbers, also describe a class compiled without debug information.

Nothing is lost. A proxy reaches the method it overrides by calling `super`, so the class it proxies carries the real counts. Reporting a proxy would add rows that name methods nobody can delete, and every proxy would trip the blind-spot warning, since it has no class file for the scan to find. Instances of one service would also disagree about its name, which carries a counter or a hash.

## Log lines

Every message starts with `otherlode:`. In the JVM's default format, the logger puts the level in front, as in `WARNING: otherlode:`, below a line with the time and the class that logged it.

| Level | Line (abridged) | Meaning |
|---|---|---|
| `WARNING` | `<class> cannot be defined: its supertype <type> could not be read from its loader, so it is not instrumented` | The class is skipped. |
| `WARNING` | `the class loader <loader> cannot see dev.otherlode.bootstrap, so the agent does not instrument the classes it defines; ...` | Every class of the loader is skipped. The line is logged once per loader. |
| `WARNING` | `instrumentation failed for <class>, class will run uninstrumented` | The class is skipped, and the stack trace follows. |
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
| `WARNING` | `<class> is reported as skipped: its manifest entries alone exceed the collector's limits ...` | A loaded class's manifest entries are too large to send, so it is reported as skipped and its hits are not sent. |
| `WARNING` | `failed to send static baseline chunk <i> of <n>; ...` | A chunk will be sent again. |
| `WARNING` | `the collector refused static baseline chunk <i> of <n> with status <code>, ...` | The collector refused a chunk with a `400` or `422`. The agent does not resend the scan. |
| `INFO` | `the static baseline reached the collector after a retry` | A failed chunk got through. |
| `WARNING` | `could not read <file> for the class listing, so no class listing is sent: <cause>` | A directory, jar or nested jar could not be read, so this process sends no class listing. |
| `WARNING` | `java.class.path is empty or has an empty element, which the JVM reads as the working directory, so no class listing is sent` | Name the classpath, or remove the empty element, such as a leading or trailing `:`, to get a listing. |
| `WARNING` | `this JVM runs from a module path or a linked image, which the class listing does not read, so no class listing is sent` | The application runs from the module path or a `jlink` image. No listing is sent, so Otherlode removes no code for this process. |
| `WARNING` | `the class listing failed, so no class listing is sent` | An unexpected error stopped the listing. The stack trace follows. |
| `WARNING` | `failed to send class listing chunk <i> of <n>; ...` | A chunk will be sent again. |
| `WARNING` | `the collector refused class listing chunk <i> of <n> with status <code>, ...` | The collector refused a chunk with a `400` or `422`. The agent does not resend the listing. |
| `INFO` | `the class listing reached the collector after a retry` | A failed chunk got through. |
