---
title: Methods and branches
description: What the agent counts in your methods and conditionals, what it leaves out, and how it labels a method or an outcome that no one should delete.
order: 30
---

The agent counts two things in the classes that `includePackages` selects: how often each method is entered, and how often each outcome of each conditional is taken. Each count is a cumulative total since the process started. The agent reports counts, marks and descriptions, and Otherlode decides what is dead.

Endpoints are on [endpoints](endpoints), whole classes on [classes](classes), and call edges on the [call graph](call-graph) page.

## Methods

### Methods that get an entry probe

Each of these gets one counter, incremented when the method is entered:

- Instance and static methods with a body, in classes and interfaces, interface default methods included.
- Constructors.
- Property accessors generated for Kotlin properties.
- Lambda bodies: Kotlin's `main$lambda$0`, javac's `lambda$main$0`, Scala 2's `$anonfun$f$1` and Scala 3's `f$$anonfun$1`. The agent also checks that an `invokedynamic` in the same class creates the lambda from the method, except for a Scala 3 lambda lifted out of a nested class. A method you wrote with a similar name does not qualify, and a named method passed by reference is an ordinary method.
- A suspend lambda's `invokeSuspend`, which holds the body.
- The static initialiser of a class that declares one. It is a state of its class and never a row of its own. See [classes](classes).

### Methods left out

These get no entry probe:

| Method | Reason |
|---|---|
| Abstract and native methods | They have no body. |
| Bridge methods | A call with the exact signature never enters the bridge. |
| Other synthetic methods, such as `access$` accessors, enum `$values` and Kotlin's `$default` | They hold no code of yours. A `$default` method gets omission probes instead. See [Optional arguments](#optional-arguments). |
| Methods a JPA enhancer adds to an entity | See [Methods a JPA enhancer adds](#methods-a-jpa-enhancer-adds). |
| A suspend lambda's `create` and `invoke` | `invokeSuspend` carries the count. |
| Methods of a suspend function's continuation class, a framework-generated proxy and similar classes | These classes get no probes at all. See [classes](classes). |

A call into a method that is left out counts toward the method it forwards to. See the [call graph](call-graph).

### Methods a JPA enhancer adds

A JPA enhancer rewrites your entity class at build time or as the class loads. The agent leaves out the methods the enhancer added. It recognises Hibernate by the method name, and Ebean, EclipseLink and OpenJPA by the enhancer's marker among the class's direct interfaces:

| Library | Marker interface | What the agent leaves out |
|---|---|---|
| Hibernate | none | Methods named `$$_hibernate_...`. |
| Ebean | `io.ebean.bean.EntityBean` | Methods named `_ebean_...`, the constructor that takes an `EntityBean`, `toString(ToStringBuilder)`, and the `toString()`, `equals()` and `hashCode()` Ebean adds. A `toString()`, `equals()` or `hashCode()` you wrote keeps its probe. |
| EclipseLink | `org.eclipse.persistence.internal.weaving.PersistenceWeaved` | Methods named `_persistence_...` that have no line-number table, and the constructor that takes a `PersistenceObject`. A `_persistence_` method you wrote has line numbers and keeps its probe. |
| OpenJPA | `org.apache.openjpa.enhance.PersistenceCapable` | The fixed set of methods OpenJPA 4.1 adds without a line-number table, such as `pcReplaceField`, `pcProvideField` and, on a serializable entity, `writeObject` and `readObject`, and a static `pcGet<field>` or `pcSet<field>` for a field the class declares. A method of yours called `pcRate()` keeps its probe. |

A call from your code to one of these methods counts toward what the method calls.

The agent probes the methods the class file declares. A method an enhancer adds as the class loads is not in that file, so it gets no probe.

The enhancers also change methods you wrote:

- **EclipseLink with property access** puts a check, such as a test of `_persistence_listener`, before the first line of your getter or setter. When the weaving ran at build time, that check gets no branch probe and every branch you wrote is probed. When EclipseLink weaves as the class loads, a getter or setter it changed keeps its entry probe and gets no branch probes.
- **OpenJPA with property access** moves your getter `getId` to `pcgetId` and puts a wrapper named `getId` in its place. When the enhancer ran at build time, the wrapper gets no probe, and the moved method reports as `getId` in its probe, its call edges and the static baseline. When OpenJPA enhances the class as it loads, the wrapper keeps its entry probe and gets no branch probes. See [compatibility](compatibility#jpa-enhancers).
- **OpenJPA on a serializable entity** adds a call to `pcSerializing()` at the start of a `writeObject` you wrote, and a test of its result before each return. When the enhancer ran at build time, that test gets no branch probe, and the method keeps its entry probe and the branch probes of your own code.

### What the agent records about a method

| Fact | Meaning |
|---|---|
| Name, descriptor, first line | The line is -1 when the class has no line-number table. |
| Static | Whether the method is `static`. |
| Parameter names | From the class file's `MethodParameters` attribute, or else from the local variable table. Empty when the class has neither, and never partial. Compiler names such as `$this$shout` are sent as written. |
| Generic signature | The method's `Signature` attribute, which keeps the generic types the descriptor erases. |
| Extension receiver | Whether the first parameter is a Kotlin extension receiver. |
| Lambda body | Whether the method is a lambda body by the rule above. |
| Inline | See [Inline functions](#inline-functions). |
| Generated, unread shape | See [Generated methods](#generated-methods) and [Unread shapes](#unread-shapes). |
| Outside caller | Whether code outside the include rules may call the method. See the [call graph](call-graph). |

The agent names a Kotlin top-level function's file from the class's Kotlin kind, not from a `Kt` suffix, so a function in a file that `@file:JvmName` renames is still named by its source file.

## Branches

### Which jumps get probes

A branch site is one conditional jump or switch in a method's bytecode. Each outcome of a kept site gets its own counter.

- A conditional jump has two outcomes: the jump taken and the fall-through. This covers `if`, `while`, `for`, `&&`, `||`, `?:`, `?.` and the like, once the compiler has lowered them to jumps.
- A table or lookup switch, from a `switch`, `when` or `match`, has one outcome per case entry plus the default. Two case labels that share a body are two outcomes. The gap entries javac adds to a sparse switch belong to the default.
- An unconditional `goto` gets no counter.

Each outcome has a counter of its own, so it never shares one with the merge point after an `if` or with another case that falls into the same code.

The branch index of an outcome counts every outcome of every site in the class, in bytecode order, kept or not. It names the outcome within one build only, since a new conditional early in a class renumbers every later outcome. The branch key, under [Describing an outcome](#describing-an-outcome), names an outcome across builds.

### Sites that get no probe

The agent leaves a site without a probe when it is not code you wrote. The site keeps its place in the numbering.

| Dropped site | What it is |
|---|---|
| Inlined copy from out-of-scope code | kotlinc copies the body of an `inline` function, such as `map`, `firstOrNull` or a library's `respond`, into each caller. When the inline function's class is outside the include rules, the copied jumps get no probe. |
| Coroutine machinery | The state machine kotlinc adds to a suspend function or suspend lambda: the switch on the continuation's label, the compare against the suspended marker and the re-entry tests at the start. Your own `if` in the same function is kept. |
| String and enum switch lowering | The `hashCode` switch, the `equals` checks that pick a case index, and the null check on the subject. See [Switches](#switches). |
| A switch's added throwing default | The default that only throws `MatchException`, `IncompatibleClassChangeError` or `NoWhenBranchMatchedException` for an exhaustive switch. |
| The collision side of a string `when` | In a Kotlin `when` or Scala `match` over strings, the not-equal side of the last `equals` check in a hash bucket. |
| A method too large to weave | See [Large methods](#large-methods). |
| A check a JPA enhancer adds | See [Methods a JPA enhancer adds](#methods-a-jpa-enhancer-adds). |

An inlined copy whose inline function is in scope is kept, and so is a copy from the same class. Its line is the line in the inline function's own file, and the outcome carries the class it was copied from, which the testkit shows as `ProbeRef.inlinedFromClassName`.

The agent logs one INFO line on the first flush that finds any dropped site:

```text
otherlode: left 41 branch sites in 12 classes without a probe: 30 inlined from out-of-scope code, 6 coroutine machinery, 4 switch lowering, 1 in methods whose branch probes would cross a code-size limit, 0 added by a JPA enhancer
```

The agent finds an inlined copy through the class's source mapping, which a class compiled without debug information lacks. There, a copy reads as the caller's own code. In a class without a local variable table, the agent cannot recognise the class's own inline functions either. A Kotlin class with no line-number table gets one WARNING:

```text
otherlode: com.example.Orders is a Kotlin class with no line-number table; its probes carry no line numbers, and its inlined copies cannot be traced
```

Coroutine recognition does not need debug information.

### Large methods

HotSpot never compiles a method over 8000 bytes of bytecode (`HugeMethodLimit`), and a class file holds at most 65535 bytes of code per method. For each method, the agent computes the largest size its probes could take it to, and drops its branch probes, keeping the entry probe, when:

- the method is 8000 bytes or smaller and the probes could take it past 8000, or
- the probes could take it past 65535.

The agent logs one WARNING per such method, naming the class and method. The method then reads as hit or not hit, and none of its outcomes is reported.

If the entry probe alone could take a method of 8000 bytes or fewer past 8000, the agent keeps all its probes and logs a WARNING that HotSpot may not compile it. A method already over 8000 bytes keeps its branch probes.

### Describing an outcome

The agent sends each kept site with the facts a person needs to find the code. Of these, the testkit shows only the branch key, as `ProbeRef.branchKey`.

**Condition.** The expression the site tests, written in the class's source language: Kotlin, Java, Scala 2 or Scala 3. It reads the way the fall-through side reads it, so for a plain `if (c) A` the condition is the `c` you wrote. It reads as source, not bytecode: Kotlin `==`, property reads, `is` and `!is`, and a string template written as a `+` concatenation; Java `equals` calls, `instanceof`, array reads and casts; Scala `==`, `eq`, `ne` and `isInstanceOf[...]`.

A string literal in a condition is sent in clear text. See [data sent](data-sent).

A part the agent cannot write in source terms, such as a call to an unrelated method or a local with no name, becomes a placeholder. A site whose whole condition would be a placeholder has no condition. Local variable names come from the local variable table, which javac writes only with `-g`, so a Java class compiled without it has a placeholder for each local.

**Role.** Which outcome of the site this is: taken, fall-through, a case, or the default.

**Guarded lines.** The lines that run only through this outcome, sent as two lists: whole lines, and lines that also hold code reached another way. An outcome can guard nothing, as the skip side of an `if` with no `else` does. A guarded line inside an in-scope inlined copy is named at its line in the inline function's file. See [what a row shows](/docs/server/findings#what-a-row-shows).

**Guard.** The innermost kept outcome that a site, or a call, sits behind. A dropped site is looked through to the next kept outcome. The testkit's `neverHit()` folds a site behind a never-taken outcome into that outcome.

**Branch key.** An opaque token that names an outcome across builds and instances, compared only for equality. The agent derives it from the class, method, descriptor, the instructions that compute the condition, and the outcome. Local variable names count and slot numbers do not, so a local added elsewhere in the method leaves the key alone. These change an outcome's key:

- Editing the condition.
- Renaming a local variable the condition reads.
- Renaming the method or changing its signature.
- A compiler upgrade that changes the bytecode.

An outcome has no key when two sites in one method share the same condition instructions, or when the agent cannot fingerprint the condition with confidence. A class compiled without a local variable table loses keys for conditions on same-typed locals. The site has a key made the same way without the outcome.

### Switches

A `when` or `switch` over a string or an enum does not reach bytecode as one switch over your values. The agent reads these shapes back, so each case is one outcome named by its label:

| Source | What the agent reads |
|---|---|
| Java `switch` on a string | One site, each case named by its string literal. |
| Java and Kotlin enum `switch` or `when` | One site, each case named by its constant. A `null` case is named `null`. |
| Java pattern `switch` | One site, each case named by its type or constant. |
| Kotlin `when` and Scala `match` on a string | No switch. Each `equals` check is an ordinary site with the condition `subject == "literal"` or `subject != "literal"`. |
| Scala enums, `Enumeration` values and sealed objects | Ordinary sites, since they compile to chains of equality checks. |

A rebuilt case label is part of the condition: a string label is a literal and an enum label is code. A rebuilt case keeps its key when you add, remove or reorder other cases, because the key digests the label and not the position.

A switch the agent does not read keeps its plain numeric case values. In a Kotlin string `when` or Scala string `match` whose lowering the agent does not read, the collision side of each hash bucket's last check is an unread shape, and every other site stays ordinary.

### Routine outcomes

A routine outcome is probed and counted, but it is no finding. The agent reads each outcome's path in the bytecode and marks it with a routine kind. The testkit's `neverHit()` leaves routine outcomes out, and `neverHitRoutineOutcomes()` lists them.

| Kind | What it is |
|---|---|
| `NULL_DEFAULT` | The null side of a null check whose path only yields null, a constant or a local, or returns early. The null side of `value?.toDoubleOrNull()` and of `?: 0.0` is routine. |
| `THROW_ONLY` | A path that only builds an exception, throws it and leaves the method. A `lateinit` check, `?: error("...")` and `if (amount < 0) throw BadRequest()` qualify. |
| `FINALLY_COPY` | An outcome of a site in the exception-path copy of a `finally` body. |

Each kind has limits:

- `NULL_DEFAULT` needs a null check whose null side calls nothing and counts nothing. `cache[key] ?: loadFromDb(key)` is ordinary. The non-null side is never routine.
- `THROW_ONLY` allows calls only to build the exception or its message: the exception's constructor, `StringBuilder`, `String.valueOf`, `toString` and string concatenation. A log call on the path makes it ordinary, and so does a handler in the same method that catches the throw and lets the method carry on.
- `FINALLY_COPY` needs a normal-path twin of the same condition outside the `finally` handler. A `try` that can leave only by an exception has no twin, so its copy is ordinary. A site in an ordinary `catch` block is never routine.
- Kotlin's `!!` compiles to a call to `Intrinsics.checkNotNull`, so it has no site.

An outcome in a method that is an [unread shape](#unread-shapes) carries that shape and can carry a routine kind too. `neverHitUnreadShapes()` lists it, and `neverHitRoutineOutcomes()` does not. An outcome that is an unread shape on its own carries no routine kind.

## Optional arguments

An optional parameter is a value parameter with a default, so a caller can leave it out. The agent counts omissions per parameter. Java has no default arguments, so nothing is counted for it.

### Kotlin

kotlinc compiles a function with defaults to the function `f` and a synthetic `f$default`, which takes a bitmask of the omitted parameters, fills in each default and calls `f`. A constructor with defaults gets the same shape, with a `DefaultConstructorMarker` parameter. A caller that omits nothing calls `f` directly.

The agent puts one omission probe per optional parameter at the entry of `f$default`, and increments it when that parameter's bit is set. It reports the probe on `f`, not on `f$default`. An omission probe carries:

- The parameter's zero-based index among the value parameters. Receivers are not counted.
- The parameter's name from `f`'s local variable table, empty when the class has none.
- Whether `f` can be overridden. This is false for a constructor, a static, private or final method, and a method on a final class, and true for an interface method.
- The line of the parameter's default value, or -1 when the method has no line-number table.

The agent recognises `$default` from its body, never from an annotation. `@JvmOverloads` overloads call `$default` with a mask, so their calls count too. Two cases each log one INFO line:

- A `$default` whose target the agent cannot resolve uniquely, or whose body has no mask test, leaves the function without omission probes:

  ```text
  otherlode: com.example.Orders#place$default(Lcom/example/Orders;IIILjava/lang/Object;)V looks like a Kotlin default-argument method but its target could not be uniquely resolved, or no mask test was found; no omission probes woven
  ```

- A function with more than 32 optional parameters keeps probes for the first 32 only:

  ```text
  otherlode: com.example.Orders#place$default tests a mask int past the first; only the first 32 optional parameters are counted
  ```

### Scala

scalac writes a public default getter `f$default$N` for each optional parameter, and a call that omits parameter N calls it. The agent reports the getter's entry as an omission of parameter N on `f`, with index N - 1, and the getter gets no method probe. A Scala extension receiver counts in N, since it is a JVM parameter. The getter must resolve to exactly one method in the same class whose Nth parameter has the getter's return type or is a by-name `Function0`. If it does not, the agent reports the getter as an ordinary method named `f$default$N` and logs one INFO line:

```text
otherlode: com.example.Orders#place$default$2()I looks like a Scala default getter but its target could not be uniquely resolved; reported as an ordinary method probe
```

A constructor default is declared on the companion's module class and also as a static forwarder on the class itself. Both resolve to the same constructor, so one parameter can carry two omission probes, and its omissions are their sum. A getter's line is its own first line, and -1 for the static forwarder, which has no line-number table.

### Findings

The testkit offers `neverSupplied()`, `alwaysSupplied()` and `omissionCount(className, methodName, parameterIndexOrName)`. See [the testkit API](testkit-api). `neverHit()` leaves omission probes out, since a zero there means the caller always supplied the value. Otherlode's own rules are under [Never supplied](/docs/server/findings#never-supplied).

## Inline functions

kotlinc copies a Kotlin `inline` function's body into each Kotlin caller. The function's own probe then counts only calls from Java and calls through a function reference, so its zero says nothing about whether the code ran. The agent keeps the probe and marks the method inline. It recognises the method from a local variable the compiler writes at the start of its body, so in a class without debug information the method loses the mark and reads as ordinary code.

Otherlode makes no never-hit claim about an inline method or a branch inside it. See [Never hit](/docs/server/findings#never-hit). The testkit's `neverHit()` lists no inline method, while `wasHit` and `hitCount` stay raw.

The copies of an inline function in a caller are separate branch sites. See [Sites that get no probe](#sites-that-get-no-probe).

## Generated methods

A generated method is one the compiler writes from a declaration rather than from a body you wrote. The agent keeps its entry probe and its branch probes, reports its count, and marks it with what generated it. Otherlode makes no never-hit claim about a generated method, and the testkit's `neverHit()` leaves it out. A generated method is no node in the [call graph](call-graph), and a call to a generated forwarder counts toward the method it forwards to.

| Mark | Language | Methods |
|---|---|---|
| `ENUM` | Kotlin, Java, Scala 3 | An enum's `values`, `valueOf` and kotlinc's static `getEntries`, and Scala 3 enum plumbing. A `getEntries` you wrote stays unmarked. |
| `DATA_CLASS` | Kotlin | A data class's `componentN` and `copy`, and whichever of `equals`, `hashCode` and `toString` you did not write. A data object's `equals`, `hashCode` and `toString`. |
| `VALUE_CLASS` | Kotlin, Scala | Kotlin: a value class's `equals-impl`, `equals-impl0`, `hashCode-impl` and `toString-impl`, the boxed methods that only forward to a static `-impl` method, and the static that calls an interface default the class inherits. Scala: a value class's boxed methods that only call the companion's `$extension` method, and the companion's `hashCode$extension` and `equals$extension`. |
| `KOTLINX_SERIALIZATION` | Kotlin | The kotlinx.serialization plugin's output: the members of a `$serializer` class, the `serializer()` of a companion or object, the `generatedSerializer()` beside `@KeepGeneratedSerializer`, and the lambda class that builds the cached serializer. |
| `RECORD` | Java | A record's `equals`, `hashCode` and `toString` when the body is javac's own `invokedynamic`. Accessors stay unmarked. |
| `DEFAULT_IMPLS` | Kotlin | A `$DefaultImpls` method that only forwards to the interface's default method, and a class's stub that only forwards to `$DefaultImpls`, including a stub whose `$DefaultImpls` method does not exist. |
| `JVM_OVERLOADS` | Kotlin | An overload that `@JvmOverloads` adds, whose body only forwards to its own class's `$default`. |
| `MULTIFILE_FACADE` | Kotlin | A function of a `@file:JvmMultifileClass` facade whose body only forwards to the same function on a part class. |
| `CASE_CLASS` | Scala | A case class's plumbing, such as `canEqual`, `copy`, `equals`, `hashCode`, `toString`, `productArity`, `productElement` and `productPrefix`, and its companion's `apply`, `unapply`, `toString` and `fromProduct`. A case value class's plumbing, in the class and in its companion. |
| `STATIC_FORWARDER` | Scala, Kotlin | Scala: a static method on an object's class that only calls the same method on the object. Kotlin: the static method kotlinc adds for a `@JvmStatic` function or property accessor in a companion object. |
| `SCALA_OBJECT` | Scala | An object's `writeReplace`. In a Scala 2 object with no `writeReplace` of scalac's, its `readResolve`. |
| `CLASS_DELEGATION` | Kotlin | A method kotlinc writes for interface delegation (`class C(d: I) : I by d`) for each member the class does not override. |
| `TRAIT_FORWARDER` | Scala | A class's method that only calls a trait's static `m$`, and the trait's `m$` that only calls `m`. A class's method that only calls the same method's default in a Java interface the class implements directly. A specialised method such as `apply(int)` that only calls its `apply$mcII$sp` twin. |

The agent reads each mark from the method's bytecode. It never takes a data class, value class or case class from the Kotlin or Scala metadata. The rules aim to leave every method you wrote unmarked. These cases tell you how one reads:

- **Members kotlinc writes without a line-number table.** A data class's `componentN`, `copy`, `equals`, `hashCode` and `toString`, a data object's `equals`, `hashCode` and `toString`, a value class's `equals-impl`, `equals-impl0`, `hashCode-impl` and `toString-impl`, and a delegation forwarder are marked only when they have no line-number table. One you wrote has line numbers and stays ordinary. A data object's three are marked only together: if you write its `toString`, its `equals` and `hashCode` stay unmarked too. In a class compiled without line numbers, a data class's members are all marked.
- **Your method compiles to the compiler's bytes.** It is marked. A Scala override that only calls `super.m` and a Kotlin secondary constructor that matches a `@JvmOverloads` overload are two examples. The agent tells a Kotlin override that only calls `super` from kotlinc's `DEFAULT_IMPLS` stub by its line, so in a class written on one line or compiled without line numbers the override can be marked.
- **Your method has a body of its own.** It is ordinary code. A `$DefaultImpls` method that holds the interface method's real body, as under `-jvm-default=disable`, is ordinary.
- **A Scala body.** It is marked only when it matches, instruction for instruction, the body scalac writes for that member of that class. Otherwise it can be an unread shape, as [Unread shapes](#unread-shapes) says.
- **Kotlin property accessors.** They are never marked, so a never-called setter stays a finding.
- **Delegation through a generic interface.** A forwarder for a delegated interface with a type argument, such as `Source<String>`, is not marked and reads as ordinary code.
- **kotlinx.serialization.** A `$serializer` class is the plugin's when it carries the plugin's markers, such as `GeneratedSerializer` and `@Deprecated` at level `HIDDEN`. A class named `$serializer` that you declared lacks them and stays ordinary. A `serializer()` that takes one `KSerializer` per type parameter, in the companion of a serializable class or in a serializable object, is marked whatever its body. A `@Serializable` class built without the plugin can therefore have a `serializer()` of yours marked. kotlinc's K2 frontend marks the plugin's `$serializer` class synthetic, and the agent does not probe a synthetic class.

## Unread shapes

An unread shape is a probe whose code has the outline of compiler output but whose body matches no shape the agent has read for that compiler. It says the agent cannot tell who wrote the code. The agent counts the probe and reports its hits, and Otherlode makes no finding from it and lists it apart with its family. The testkit's `neverHit()` leaves unread shapes out, and `neverHitUnreadShapes()` lists them.

| Family | In the WARNING | Outline |
|---|---|---|
| `CASE_CLASS` | Scala case-class plumbing | A plumbing-named method of a case class or its companion. |
| `STATIC_FORWARDER` | Scala static forwarders | A static method with a `$` twin of the same name and descriptor. |
| `SCALA_OBJECT` | Scala object serialization | An object's `writeReplace` or `readResolve`. |
| `SCALA_ENUM` | Scala enum plumbing | Scala 3 enum plumbing. |
| `MULTIFILE_FACADE` | Kotlin multi-file facade methods | A method of a Kotlin multi-file facade that is not a recognised forwarder. |
| `COROUTINE_MACHINERY` | coroutine machinery | A jump or switch in a suspend method's state machine that matches none of the shapes the agent reads. |
| `STRING_SWITCH` | string switch lowering | The collision side of a string switch's hash bucket, when the agent cannot read the lowering. |
| `VALUE_CLASS` | Scala value class forwarders | A Scala value class's boxed method, or its companion's `hashCode$extension` or `equals$extension`. |
| `TRAIT_FORWARDER` | Scala trait forwarders | A class's method that calls its trait's static `m$` twin, the same method's default in a Java interface, or its own `$mc...$sp` twin, or a trait's static `m$`. |
| `KOTLINX_SERIALIZER_OBJECT` | kotlinx.serialization serializer objects | The `serialize`, `deserialize` and `getDescriptor` of an object annotated `@Serializer(forClass = ...)`. The plugin fills in the ones you did not write, and the bytecode does not say which those are. |

Every family but the last covers a method or outcome in that outline whose body the agent has not read. A branch probe inherits its method's unread shape, and an omission probe inherits its target's. A branch outcome can also be an unread shape on its own, as `COROUTINE_MACHINERY` or `STRING_SWITCH`.

Whether a body counts as unread depends on whether the class names its compiler:

- **Scala 3.** A class names the release that compiled it. On a release the agent has read, a plumbing-named method that fails its rule is hand-written and ordinary, except for methods scalac does not let you write, such as the methods of an enum case's class, which stay unread shapes. On a release the agent has not read, the method is an unread shape. A method with a forwarder's call shape whose rule needs a class the agent cannot load, such as scala-library's `Function1` when the class's loader does not serve it, is an unread shape on any release. [Compatibility](compatibility#compilers) lists the releases the agent has read.
- **Scala 2.** A class does not name its compiler. A method in the outline that matches no read shape is an unread shape, and that includes a hand-written override of case-class plumbing, such as your own `toString` in a case class.
- **Coroutine and string-switch outlines.** They hold only shapes no source produces, so the result does not depend on a compiler release.
- **`@Serializer(forClass = ...)` objects.** Their `serialize`, `deserialize` and `getDescriptor` are unread shapes whatever the compiler.

The agent logs one WARNING on the first flush that finds any unread shape. It counts them per family, names each Scala 3 release the agent has not read, and names the Scala 2 case apart when one applies:

```text
otherlode: 12 methods in 3 classes look like compiler output this agent has not read, and are reported as unread shapes rather than dead code (9 Scala case-class plumbing, 3 Scala object serialization). Scala 3 releases this agent has not read: 3.11.0. A newer agent may read them.
```

A Scala 3 class from a release the agent has not read also gets one WARNING of its own:

```text
otherlode: com.example.Order was compiled by Scala 3.11.0, which this agent has not read; 9 methods that look like compiler output are reported as unread shapes
```

## Where you see these marks

The testkit's queries cover the routine, unread-shape and optional-parameter marks: `neverHit()` for findings, `neverHitRoutineOutcomes()` for routine outcomes, `neverHitUnreadShapes()` for unread shapes, and `neverSupplied()`, `alwaysSupplied()` and `omissionCount(...)` for optional parameters. On every `ProbeRef` a query returns, `inline` is false and `generatedBy` is null, including a generated caller that `unreachedClusters()` lists in `reachedFrom`. A `ProbeRef` also carries `routine`, `unreadShape`, `branchKey` and `inlinedFromClassName`, and `routine` and `unreadShape` are null when the mark does not apply.

The testkit's enums `ProbeKind`, `GeneratedBy`, `RoutineKind` and `UnreadShape` can gain values in a minor release, so a `when` over one needs an `else` branch. See [the testkit API](testkit-api).
