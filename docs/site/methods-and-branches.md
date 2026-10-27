---
title: Methods and branches
description: What the agent counts in your methods and conditionals, what it leaves out, and how it labels a method or an outcome that no one should delete.
order: 30
---

The agent counts two things inside the classes that `includePackages` selects: how often each method is entered, and how often each outcome of each conditional is taken. This page lists what gets a counter, what does not, and what the agent records about each one so that Otherlode can describe it in your source's terms. Endpoints are on [endpoints](endpoints), whole classes on [classes](classes), and call edges on the [call graph](call-graph) page.

Every count is a cumulative total since the process started. The agent decides nothing about whether code is dead. It reports counts, marks and descriptions, and Otherlode judges.

## Methods

### Methods that get an entry probe

Each of these gets one counter, incremented when the method is entered:

- Instance and static methods with a body, in classes and interfaces. An interface default method counts.
- Constructors.
- Property accessors generated for Kotlin properties.
- Lambda bodies: Kotlin's `main$lambda$0`, javac's `lambda$main$0`, Scala 2's `$anonfun$f$1`, and Scala 3's `f$$anonfun$1`. The agent also checks that an `invokedynamic` in the same class creates the lambda from the method, except for a Scala 3 lambda lifted out of a nested class. A method you wrote with a similar name does not qualify, and a named method passed by reference is an ordinary method.
- A suspend lambda's `invokeSuspend`, which holds the body.
- The static initializer, but only for a class that declares one. A class with no static initializer, such as a marker interface, gets no such counter. The static initializer is a state of its class and never a row of its own. See [classes](classes).

### Methods left out

These get no entry probe:

| Method | Reason |
|---|---|
| Abstract and native methods | There is no body to count in. |
| Bridge methods | A call with the exact signature never enters the bridge, so it would read zero forever. |
| Other synthetic methods, such as `access$` accessors, enum `$values` and Kotlin's `$default` | They hold no code of yours. A `$default` method gets optional-argument probes instead. See [Optional arguments](#optional-arguments). |
| Methods a JPA enhancer adds to an entity | Hibernate, Ebean, EclipseLink and OpenJPA add them to the class you wrote. They stand for nothing in your source. See [Methods a JPA enhancer adds](#methods-a-jpa-enhancer-adds). |
| A suspend lambda's `create` and `invoke` | Which of the two runs depends on how a coroutine library starts the lambda, not on your code. `invokeSuspend` carries the count. |
| A suspend function's continuation class, a framework-generated proxy and similar classes | They have no probes at all. See [classes](classes). |

A call into a method that is left out counts toward the method it forwards to. See the [call graph](call-graph).

### Methods a JPA enhancer adds

A JPA enhancer rewrites your entity class, at build time or when the class loads. The agent leaves out what the enhancer added, so those methods never read as code you could delete. It recognises Hibernate by the method name, and Ebean, EclipseLink and OpenJPA by the enhancer's marker among the class's direct interfaces:

| Library | Marker interface | What the agent leaves out |
|---|---|---|
| Hibernate | none; the name is enough | Methods named `$$_hibernate_...`. |
| Ebean | `io.ebean.bean.EntityBean` | Methods named `_ebean_...`, the constructor that takes an `EntityBean`, `toString(ToStringBuilder)`, and the `toString()`, `equals()` and `hashCode()` Ebean adds. A `toString()`, `equals()` or `hashCode()` you wrote keeps its probe. |
| EclipseLink | `org.eclipse.persistence.internal.weaving.PersistenceWeaved` | Methods named `_persistence_...` that have no line-number table, and the constructor that takes a `PersistenceObject`. A `_persistence_` method you wrote has line numbers and keeps its probe. |
| OpenJPA | `org.apache.openjpa.enhance.PersistenceCapable` | The fixed set of methods OpenJPA 4.1 adds without a line-number table, such as `pcReplaceField`, `pcProvideField` and, on a serializable entity, `writeObject` and `readObject`. A static `pcGet<field>` or `pcSet<field>` for a field the class declares. A method of yours called `pcRate()` keeps its probe. |

A call from your code to one of these methods counts toward what the method calls, like any left-out method.

With property access, EclipseLink also puts a check into your getter or setter before its first line, such as a test of `_persistence_listener`. When the weaving ran at build time, that check gets no branch probe, and every branch you wrote in the method is probed as usual. When EclipseLink weaves at load time, a getter or setter it changed keeps its entry probe and gets no branch probes.

With property access, OpenJPA moves your getter or setter `getId` to `pcgetId` and puts a wrapper named `getId` in its place. When the enhancer ran at build time, the wrapper gets no probe, and the moved method reports under the name you wrote, so a probe, a call edge and the static baseline all say `getId`. When OpenJPA enhances the class as it loads, a wrapper keeps its entry probe and gets no branch probes, as EclipseLink's woven methods do. Enhance OpenJPA entities that use property access at build time to keep the branches you wrote; see [compatibility](compatibility#jpa-enhancers).

The agent probes the methods the class file on disk declares. A method an enhancer adds as the class loads is not in that file, so it gets no probe.

### What the agent records about a method

Each probed method carries these facts to the collector.

| Fact | Meaning |
|---|---|
| Name, descriptor, first line | The line is -1 when the class has no line-number table. |
| Static | Whether the method is `static`. Otherlode uses it to tell a class meant to have instances from a holder of static functions. |
| Parameter names | Read from the class file's `MethodParameters` attribute, or else from the local variable table. Empty when the class was compiled without either, and never partial. Compiler names such as `$this$shout` are sent as written. |
| Generic signature | The method's `Signature` attribute, which keeps the generic types the descriptor erases. |
| Extension receiver | Whether the first parameter is a Kotlin extension receiver. |
| Lambda body | Whether the method is a lambda body by the rule above. |
| Inline | Whether the method is a Kotlin `inline` function. See [Inline functions](#inline-functions). |
| Generated, unread shape | See [Generated methods](#generated-methods) and [Unread shapes](#unread-shapes). |
| Outside caller | Whether code outside the include rules may call the method. See the [call graph](call-graph). |

The agent reads a Kotlin top-level function's file from the class's Kotlin kind, never from a `Kt` suffix, so a function in a file that `@file:JvmName` renames is still named by its source file.

## Branches

### Which jumps get probes

A branch site is one conditional jump or switch in a method's bytecode. Each outcome of a kept site gets its own counter.

- A two-outcome conditional has two outcomes: the jump taken and the fall-through. This covers `if`, `while`, `for`, `&&`, `||`, `?:`, `?.` and the like, after the compiler has lowered them to jumps.
- A `switch`, `when` or `match` that compiles to a table or lookup switch has one outcome per case entry plus the default. Two case labels that share a body are still two outcomes. The gap entries javac adds to a sparse switch belong to the default and are not separate cases.
- An unconditional `goto` has one successor and no counter.

The agent counts an outcome with a private edge of its own, so an outcome never shares a counter with the merge point after an `if` or with another case that falls into the same code.

A site gets a position in the class's numbering whether or not it is kept. The branch index of an outcome counts every outcome of every site in the class, in bytecode order. It names the outcome within one build only. One new conditional early in a class renumbers every later outcome, so the branch index never identifies an outcome across builds. Use the branch key, described under [Describing an outcome](#describing-an-outcome), for that.

### Sites that get no probe

The agent leaves a site without a probe when it is not code you wrote. The site keeps its place in the numbering.

| Dropped site | What it is |
|---|---|
| Inlined copy from out-of-scope code | kotlinc copies the body of an `inline` function into each caller. A copy of `map`, `firstOrNull` or a library's `respond` brings that library's jumps into your method. When the inline function's class is outside the include rules, the copies get no probe. |
| Coroutine machinery | The state machine kotlinc adds to a suspend function or suspend lambda: the switch on the continuation's label, the compare against the suspended marker, and the re-entry tests at the start of the function. Your own `if` in the same function is kept. |
| String and enum switch lowering | The `hashCode` switch, the `equals` checks that pick a case index, and the null check on the subject. The agent reads your cases from the lowering instead. See [Switches](#switches). |
| A switch's added throwing default | The default that only throws `MatchException`, `IncompatibleClassChangeError` or `NoWhenBranchMatchedException` for an exhaustive `when`. Your source has no such default. |
| The collision side of a string `when` | In a Kotlin `when` or Scala `match` over strings, the not-equal side of the last `equals` check in a hash bucket. Only a different string with the same hash code reaches it. |
| A method too large to weave | See [Large methods](#large-methods). |
| A check EclipseLink's weaving adds | A test of EclipseLink's `_persistence_` state before the first line of your getter or setter. See [Methods a JPA enhancer adds](#methods-a-jpa-enhancer-adds). |

An inlined copy whose inline function is in scope is kept. Its line is the line in the inline function's own file, and the outcome carries the name of the class it was copied from. The testkit shows that class as `ProbeRef.inlinedFromClassName`. A copy from the same class counts too.

The agent logs one INFO line on the first flush that finds any dropped site:

```text
otherlode: left 41 branch sites in 12 classes without a probe: 30 inlined from out-of-scope code, 6 coroutine machinery, 4 switch lowering, 1 in methods whose branch probes would cross a code-size limit, 0 added by EclipseLink's weaving
```

A class compiled without debug information has no source mapping, so the agent cannot recognize an inlined copy, and a copy reads as the caller's own code. A Kotlin class with no line-number table also gets one WARNING. Stripped debug information usually removes the local variable table too, and the agent then cannot recognize the class's inline functions either. Coroutine recognition does not need debug information.

### Large methods

A probe adds bytes. HotSpot never compiles a method over 8000 bytes of bytecode (`HugeMethodLimit`), and a class file cannot hold more than 65535 bytes of code in one method. For each method, the agent computes the largest size the probes could take it to. In two cases it drops the method's branch probes and keeps its entry probe:

- The method is 8000 bytes or smaller and the probes could take it past 8000.
- The probes could take it past 65535.

The agent logs one WARNING per such method, naming the class and method, and says its branch probes are left out. The method then reads as hit or not hit, but none of its outcomes is reported.

If the entry probe alone could take a method of 8000 bytes or fewer past 8000, the agent keeps all its probes and logs a WARNING that HotSpot may not compile it. A method already over 8000 bytes keeps its branch probes, since HotSpot does not compile it either way.

### Describing an outcome

The agent sends each kept site as a unit, with the facts a person needs to find the code. The testkit shows them on `ProbeRef`.

**Condition.** The expression the site tests, written in the class's source language: Kotlin, Java, Scala 2 or Scala 3. It is written the way the fall-through side reads it, which is the jump's own test negated. The compilers lower `if (c) A` to "jump past `A` when not `c`", so for a plain `if` the condition is the one you wrote. The condition reads as source, not as bytecode:

- Kotlin `==` for a null-safe equality call, a property for a getter call, `is` and `!is` for type tests, and a `+` concatenation for a string template.
- Java `equals` calls, `instanceof`, array reads and casts.
- Scala `==`, `eq`, `ne` and `isInstanceOf[...]`.

A string literal in a condition is sent in clear text. See [data sent](data-sent).

A part the agent cannot write in source terms, such as a call to an unrelated method or a local with no name, becomes a placeholder, printed as `…`. A site whose whole condition would be a placeholder has no condition, and is named by its line, as "the branch at line 120". Local variable names come from the local variable table, which javac writes only with `-g`, so a Java class compiled without it shows `…` for locals.

**Role.** Which outcome of the site this is: taken, fall-through, a case, or the default. A taken outcome reads "was never false" and a fall-through reads "was never true", for the condition above.

**Guarded lines.** The lines that run only through this outcome, with no other path from the method's entry. Whole lines are listed as "only path to", and lines that also hold code reached another way as "partly". An outcome can guard nothing, as the skip side of an `if` with no `else` does, and such an outcome reads "guards no code of its own". A guarded line inside an in-scope inlined copy is named at its origin line in the inline function's file.

**Guard.** The innermost kept outcome that a site, or a call, sits behind. A site behind a never-taken outcome is part of that outcome's code, not a separate finding. The testkit's `neverHit()` folds such a site into the outcome that guards it. A dropped site is looked through to the next kept outcome.

**Branch key.** An opaque token that names an outcome across builds and instances, compared only for equality. It is derived from the class, method, descriptor, the instructions that compute the condition, and the outcome. The names of local variables count and their slot numbers do not, so adding a local elsewhere in the method leaves the key alone. These change an outcome's key:

- Editing the condition itself.
- Renaming a local variable the condition reads.
- Renaming the method or changing its signature.
- A compiler upgrade that changes the bytecode.

A key is absent, and Otherlode treats the outcome as new, when two sites in one method share the same condition instructions, or when the agent cannot fingerprint the condition with confidence. A class compiled without a local variable table loses keys for conditions on same-typed locals. The site has a key made the same way without the outcome. The testkit shows an outcome's key as `ProbeRef.branchKey`.

### Switches

A `when` or `switch` over a string or an enum does not reach bytecode as one switch over the values you wrote. The agent reads these shapes back to your cases, so that each case is one outcome named by its label:

| Source | What the agent reads |
|---|---|
| Java `switch` on a string | One site, with each case named by its string literal. |
| Java and Kotlin enum `switch` or `when` | One site, with each case named by its constant. A `null` case is named `null`. |
| Java pattern `switch` | One site, with each case named by its type or constant. |
| Kotlin `when` and Scala `match` on a string | No switch. Each `equals` check stays an ordinary site with the condition `subject == "literal"` or `subject != "literal"`. |
| Scala enums, `Enumeration` values and sealed objects | They compile to chains of equality checks and are ordinary sites already. |

A rebuilt case label is part of the condition, so a string label is a literal and an enum label is code. A never-hit case reads as the subject, then the label: "`mode` was never `FAST`". A rebuilt case keeps its key when you add, remove or reorder other cases, because the key digests the label and not the case's position.

A switch the agent does not read exactly keeps its plain numeric case values. In a Kotlin string `when` or Scala string `match` whose lowering the agent does not read, only the collision side of each hash bucket's last check is affected, and the agent reports it as an unread shape. Every other site of that lowering stays an ordinary site.

### Routine outcomes

Some outcomes are real but not worth a person's time when they never run. The agent reads each outcome's path in the bytecode and marks it with a routine kind. A routine outcome is probed and counted, but it is no finding. The testkit's `neverHit()` leaves it out, and `neverHitRoutineOutcomes()` lists it.

| Kind | Reads as | What it is |
|---|---|---|
| `NULL_DEFAULT` | `default never used` | The null side of a null check whose path does nothing but yield null, a constant, a local, or return early. `value?.toDoubleOrNull()` and `?: 0.0` on their null side are routine. |
| `THROW_ONLY` | `only throws` | A path that only builds an exception and throws it, and leaves the method. A `lateinit` check, the throwing branch of an exhaustive `when`, `?: error("...")`, and `if (amount < 0) throw BadRequest()` all qualify. |
| `FINALLY_COPY` | `finally copy` | An outcome of a site in the exception-path copy of a `finally` body. |

Each kind has limits, so that the rule does not hide a real lead:

- `NULL_DEFAULT` applies to null checks only, and only when the path calls nothing. `cache[key] ?: loadFromDb(key)` calls a method on the null side, so it is an ordinary outcome. A null side that counts something is also ordinary. The non-null side is never routine.
- `THROW_ONLY` allows calls only to build the exception or its message: the exception's constructor, `StringBuilder`, `String.valueOf`, `toString` and string concatenation. A log call on the path makes it ordinary. A throw that a handler in the same method catches is ordinary when the handler lets the method carry on.
- `FINALLY_COPY` needs a normal-path twin of the same condition outside the `finally` handler. A `try` that can leave only by an exception has no twin, and its copy is ordinary. A site in an ordinary `catch` block is never routine, because error handling that never ran can be a real lead.
- Kotlin's `!!` compiles to a call on current kotlinc, so it has no site of its own.

An outcome is routine, or an unread shape, or neither. It is never both.

## Optional arguments

An optional parameter is a value parameter with a default, so a caller can leave it out. The agent counts omissions per parameter. There is nothing to count for Java, which has no default arguments.

### Kotlin

kotlinc compiles a function with defaults to the function itself plus a synthetic `f$default`. The `$default` method takes a bitmask of the omitted parameters, fills in each default, and calls `f`. A constructor with defaults gets the same shape, with a `DefaultConstructorMarker` parameter. A caller that omits nothing calls `f` directly.

The agent puts one omission probe per optional parameter at the entry of `f$default`, and increments it for each parameter whose bit is set in the mask. It reports the probe on `f`, not on `f$default`, so Otherlode joins it to `f`'s entry probe by the identity it has. An omission probe carries:

- The parameter's zero-based index among the value parameters. Receivers are not counted.
- The parameter's name, read from `f`'s local variable table, empty when the class has none.
- Whether `f` can be overridden. This is false for a constructor, a static, private or final method, and a method on a final class. It is true for an interface method.
- The line of the parameter's default value, so the probe points at the default and not at the function's first line. The line is -1 when the method has no line-number table.

The agent recognizes `$default` from its body, never from an annotation. `@JvmOverloads` overloads call `$default` with a mask, so they count too. Two situations leave a function without omission probes, each with one INFO line: a `$default` whose target the agent cannot resolve uniquely or whose body has no mask test, and a function with more than 32 optional parameters, where only the first 32 are counted.

### Scala

scalac writes a public default getter `f$default$N` for each optional parameter, and the compiler calls it for every call that omits parameter N. The getter's own entry slot is reported as an omission of parameter N on the target `f`, with index N - 1 (a Scala extension receiver counts, since it is a JVM parameter), and the getter gets no ordinary method probe. The getter must resolve to exactly one method in the same class, whose Nth parameter has the getter's return type or is a by-name `Function0`. If it resolves to none or several, the agent logs one INFO line and reports the getter as an ordinary method named `f$default$N`.

A constructor default is declared on the companion's module class and also appears as a static forwarder on the class itself. Both resolve to the same constructor, so one parameter can carry two omission probes. Otherlode sums them before it judges. The line of a re-kinded getter is the getter's own first line, and -1 for the static forwarder, which has no line-number table.

### Findings

Otherlode judges two things from an optional parameter's omission total and its function's hit total:

| Finding | Rule | Meaning |
|---|---|---|
| Never supplied | The omission total equals the function's hit total | Every caller took the default, so the parameter can go. Otherlode claims it only for a function that cannot be overridden, because an override's calls spread the counts. |
| Always supplied | The omission total is zero and the function was hit at least once | The default value is dead. |

Neither is claimed when the target is an inline function, a generated method such as a data class's `copy` (`copy(x = 1)` is how `copy` is meant to be used), or an unread shape.

The testkit offers `neverSupplied()`, `alwaysSupplied()` and `omissionCount(className, methodName, parameterIndexOrName)`. See [the testkit API](testkit-api). The never-hit report leaves omission probes out, since a zero there is "always supplied" and not dead code.

## Inline functions

kotlinc copies a Kotlin `inline` function's body into each Kotlin caller. The function's own probe then counts only calls from Java and calls through a function reference, so a zero says nothing about whether the code ran. The agent keeps the probe and marks the method inline. It recognizes the method from a local variable the compiler writes at the start of its own body, so a class without debug information loses the mark and reads as ordinary code.

The mark has these effects:

- Otherlode makes no never-hit claim about an inline method, and none about a branch inside it.
- A class whose declared methods are all inline gets no never-loaded claim.
- The testkit's `neverHit()` lists no inline method. `wasHit` and `hitCount` stay raw.

The copies of an inline function in a caller are separate branch sites. See [Sites that get no probe](#sites-that-get-no-probe) for when they are kept.

## Generated methods

A generated method is one the compiler writes from a declaration rather than from a body you wrote. The agent keeps its entry probe, and the branch probes inside it, and marks it with what generated it. Otherlode leaves a generated probe out of every never-hit finding and out of the call graph, because the compiler would write the method again whatever you do. The count is still reported, since a `copy` that is called is a `copy` in use. The testkit's `neverHit()` leaves generated methods out.

The agent reads each mark from the shape of the method's body, never from the Kotlin or Scala metadata. The testkit exposes the mark as `ProbeRef.generatedBy`, which is null for ordinary code.

| Mark | Language | Methods |
|---|---|---|
| `ENUM` | Kotlin, Java, Scala 3 | An enum's `values`, `valueOf` and `getEntries`, and Scala 3 enum plumbing. |
| `DATA_CLASS` | Kotlin | A data class's `componentN` and `copy`, and whichever of `equals`, `hashCode` and `toString` you did not write. |
| `VALUE_CLASS` | Kotlin | A value class's `equals-impl`, `equals-impl0`, `hashCode-impl` and `toString-impl`, the boxed methods that only forward to a static `-impl` method, and the static that calls an interface default the class inherits. |
| `KOTLINX_SERIALIZATION` | Kotlin | The kotlinx.serialization compiler plugin's output: the members of a `$serializer` class, the `serializer()` of a companion or object, the `generatedSerializer()` beside `@KeepGeneratedSerializer`, and the lambda class that builds the cached serializer. |
| `RECORD` | Java | A record's `equals`, `hashCode` and `toString`, only when the body is javac's own `invokedynamic`. Accessors are your declarations and stay unmarked. |
| `DEFAULT_IMPLS` | Kotlin | A `$DefaultImpls` method that only forwards to the interface's own default method, and a class's stub that only forwards to `$DefaultImpls`. The forwarder or stub may carry kotlinc's hash in its name, box a value-class argument and unbox the result, drop a `Unit` result, or, for a suspend function, return `Unit` after comparing the result with the suspended marker. Each parameter and the result must be the type the interface method takes and returns once the class's type arguments are put in. A class's stub is marked only when it is open, as kotlinc writes it, and a forwarder to a super-interface's `$DefaultImpls` only when the sub-interface does not declare the method. So an overload you wrote that only calls `super` is ordinary code when it is final. One that is open compiles to a stub's exact bytes and is marked: one you declared `open`, one that overrides another interface's method, and any member the all-open or kotlin-spring compiler plugin opens. |
| `JVM_OVERLOADS` | Kotlin | An overload that `@JvmOverloads` adds, whose body only forwards to its own class's `$default`. |
| `MULTIFILE_FACADE` | Kotlin | A function of a `@file:JvmMultifileClass` facade whose body only forwards to the same function on a part class. |
| `CASE_CLASS` | Scala | A case class's plumbing (`canEqual`, `copy`, `equals`, `hashCode`, `toString`, `productArity`, `productElement`, `productPrefix` and the rest), and its companion's `apply`, `unapply`, `toString` and `fromProduct`. |
| `STATIC_FORWARDER` | Scala | A static method on an object's class that only calls the same method on the object. |
| `SCALA_OBJECT` | Scala | An object's `writeReplace`. |

The rules are exact so that a method you wrote is never hidden:

- A data class is recognized only when `component1` through `componentN`, a matching `copy`, and `equals`, `hashCode` and `toString` are all present with the shapes the compiler writes. A class that writes some of them by hand and not the rest is not marked at all.
- A data class's `componentN`, `copy`, `equals`, `hashCode` and `toString` are marked only when the method has no line-number table, which is how kotlinc writes the generated ones. One you wrote is ordinary code, so a plain class with its own `component1` and `copy` is not marked. In a class compiled without line numbers the two cannot be told apart, so all of them are marked.
- A data class with a value-class property is recognized. kotlinc adds a hash to the name of each `componentN` and of `copy` whose signature holds the value class, such as `component1-sYCTz3k`, and the rule accepts a `-` and seven characters after either name. It does not check the hash.
- A value class is recognized by the `box-impl` and `unbox-impl` methods kotlinc adds with the synthetic flag, which source cannot set. Its `equals-impl`, `equals-impl0`, `hashCode-impl` and `toString-impl` are marked only when they have no line-number table, so a `toString` you wrote stays ordinary code. A boxed method is marked when its body only reads the class's one field, passes it and its arguments to the static method with its own name plus `-impl` (or its own name when kotlinc already mangled it), and returns. That covers `equals`, `hashCode`, `toString` and an interface method the class overrides. A boxed `equals`, `hashCode` or `toString` that forwards to a `-impl` method you wrote is ordinary code. A value class that inherits an interface's default method without overriding it gets a static `-impl` method that boxes the value and calls the default. That static is marked when its body is only that call. One that also checks a parameter for null, carries kotlinc's hash in place of `-impl`, or casts a generic result has no rule yet, so it reads as your code. `constructor-impl`, the property getter and a member you declared are never marked.
- A suspend forwarder in a sub-interface is marked only when it casts its receiver to the super-interface. An `override suspend fun run() = super.run()` you wrote compiles to the same tail without the cast and stays ordinary code.
- A `$DefaultImpls` method that holds the interface method's real body is ordinary code. This is the layout under `-jvm-default=disable`, the default up to Kotlin language version 2.1. Under `-jvm-default=enable`, the default from 2.2, only the leftover forwarder is marked.
- A hand-written secondary constructor that calls the full constructor with named arguments and leaves some out compiles to the same bytecode as a `@JvmOverloads` overload, and it is marked too.
- A Scala method is marked only when its body is instruction for instruction the one scalac writes for that member of that class. Bodies change between compiler releases, so see [Unread shapes](#unread-shapes).
- Kotlin property accessors are not generated in this sense. A never-called setter is real dead code and stays unmarked.
- A `$serializer` class is the plugin's when its enclosing class is serializable, it implements `GeneratedSerializer`, it has a static `INSTANCE` of its own type (or, in a generic class, a `typeSerial0` field) and it carries `@Deprecated` at level `HIDDEN`. Its `serialize`, `deserialize` (a value class's carry kotlinc's hash), `childSerializers`, `getDescriptor`, `typeParametersSerializers`, its constructors and `getTypeSerialN` are marked. A method with another name stays ordinary code, and so does every method of a class named `$serializer` that you declared, since it lacks the markers. kotlinc's K2 frontend marks the plugin's `$serializer` synthetic, and the agent does not probe a synthetic class. K1 does not, and it is the frontend of Kotlin 1.9 and of any release under `-language-version 1.9`.
- The `serializer()` of the companion of a serializable class, or of a serializable object, is marked when it takes one `KSerializer` per type parameter, whatever its body. The plugin fails the compile when you declare a `serializer()` of that signature there, so none of yours can match. A class is serializable when it carries `@Serializable`, or an annotation that carries `@MetaSerializable`. A companion method you wrote, such as `make()`, a `serializer(flag: Boolean)`, a `serializer()` of another class nested in the class, and a `serializer()` in a class that is not serializable stay ordinary code. The rule rests on the plugin having compiled the class: a `@Serializable` class built without the plugin can hold a `serializer()` you wrote, and it is marked. So is the `serializer()` of a final nested class whose only constructor is private and takes no arguments, held in a companion property of its own name. An annotation with `SOURCE` retention is not in the class file, so a class it makes serializable reads as not serializable. A `generatedSerializer()` in the companion of a class that carries `@KeepGeneratedSerializer` is marked when it only reads the `$serializer` class's `INSTANCE`.
- A lambda class that builds the cached serializer is marked, with its `invoke`, constructor and type initializer. kotlinc writes it as a class under K1, and under K2 with `-Xlambdas=class`. It is marked only when the type initializer of the serializable class, of its companion, or of the class that holds a serializable companion passes the lambda's `INSTANCE` to `lazy` with `PUBLICATION` and stores the result in `$cachedSerializer$delegate`, or in an array the same type initializer then stores in `$childSerializers`. If the agent cannot read the outer class, the lambda stays ordinary code.

A generated method is no node in the [call graph](call-graph). A call to a generated forwarder counts toward the method it forwards to.

## Unread shapes

An unread shape is a probe whose code has the outline of compiler output but whose body matches no shape the agent has read for that compiler. It is a statement about the agent, not about who wrote the code. The agent counts the probe and reports its hits, but Otherlode makes no finding from it, includes it in no unreached cluster, and lists it apart with its family. The testkit's `neverHit()` leaves unread shapes out and `neverHitUnreadShapes()` lists them.

| Family | Reads as | Outline |
|---|---|---|
| `CASE_CLASS` | `case class` | A case class's or its companion's plumbing-named method whose body is not read. |
| `STATIC_FORWARDER` | `static forwarder` | A static method with a `$` twin of the same name and descriptor, whose body is not read. |
| `SCALA_OBJECT` | `scala object` | An object's `writeReplace` or `readResolve` whose body is not read. |
| `SCALA_ENUM` | `scala enum` | Scala 3 enum plumbing whose body is not read. |
| `MULTIFILE_FACADE` | `multifile facade` | A method of a Kotlin multi-file facade that is not a recognized forwarder. A facade holds no code of yours. |
| `COROUTINE_MACHINERY` | `coroutine machinery` | A jump or switch in a suspend method's state machine that matches none of the shapes the agent reads. |
| `STRING_SWITCH` | `string switch` | The collision side of a string switch's hash bucket, when the agent cannot read the lowering. |
| `KOTLINX_SERIALIZER_OBJECT` | `kotlinx serializer object` | The `serialize`, `deserialize` and `getDescriptor` of an object annotated `@Serializer(forClass = ...)`. The plugin fills in the ones you did not write, and nothing in the bytecode tells which those are. |

A branch probe inherits the unread shape of its method. A branch outcome can also be unread on its own, for the last two families. An omission probe inherits its target's.

How a body counts as unread depends on whether the class names its compiler:

- A Scala 3 class names the exact release that compiled it. When the release is one the agent has read, a plumbing-named method that fails its rule is hand-written and ordinary, unless scalac does not let you write it: any method of an enum case's class; an enum companion's `fromOrdinal` and `ordinal(Object)`, its `values` when every case is a singleton, and its `$new` when one is; its `valueOf` when every case is a singleton, together with any method of yours the JVM knows as `valueOf(String)` when the agent cannot read scalac's; and, in the companion of an enum declared inside a class or a method, the method that returns each case. Those stay unread shapes. When the release is not on the list, the method is an unread shape. See [compatibility](compatibility) for the compilers the agent has read.
- A Scala 2 class does not name its compiler. A method in the outline that matches no read shape is an unread shape, and that includes a hand-written override of case-class plumbing, such as your own `toString` in a case class. The price of not knowing the compiler is that such an override lands in the unread list and not in a finding.
- The coroutine and string-switch outlines contain only shapes no source produces, so the answer does not depend on a compiler release.
- A `@Serializer(forClass = ...)` object is an unread shape whatever the compiler, since an override you wrote and the plugin's fill-in compile to the same outline. Its `serialize`, `deserialize` and `getDescriptor` are listed apart and are never called dead code. The log line counts them as members of a class where a compiler plugin fills in what you did not write.

The agent logs one WARNING on the first flush that finds any unread shape. It counts them per family, names each Scala 3 release the agent has not read, and says a newer agent may read them. It names the Scala 2 case separately when one applies:

```text
otherlode: 12 methods in 3 classes look like compiler output this agent has not read, and are reported as unread shapes rather than dead code (9 Scala case-class plumbing, 3 Scala object serialization). Scala 3 releases this agent has not read: 3.11.0. A newer agent may read them.
```

A Scala 3 class with an unread release also gets one WARNING of its own that names the release.

## Where you see these marks

The testkit's queries cover every mark on this page: `neverHit()` for findings, `neverHitRoutineOutcomes()` for routine outcomes, `neverHitUnreadShapes()` for unread shapes, and `neverSupplied()`, `alwaysSupplied()` and `omissionCount(...)` for optional parameters. A `ProbeRef` carries `inline`, `generatedBy`, `routine`, `unreadShape`, `branchKey` and `inlinedFromClassName`.

The testkit's enums `ProbeKind`, `GeneratedBy`, `RoutineKind` and `UnreadShape` can gain values in a minor release, so a `when` over one needs an `else` branch. A `ProbeRef`'s `generatedBy`, `routine` and `unreadShape` hold null when the mark does not apply. See [the testkit API](testkit-api).
