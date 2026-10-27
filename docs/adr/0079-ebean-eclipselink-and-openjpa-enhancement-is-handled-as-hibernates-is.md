---
status: accepted
---

# Ebean, EclipseLink and OpenJPA enhancement is handled as Hibernate's is

Decided on 2026-10-26 in a grill, from each enhancer's source and from `javap` of entities it enhanced: Ebean's agent 18.7.0 and 19.7.0, EclipseLink 4.0.9 and OpenJPA 4.1.1. It amends ADR 0047.

## Context

ADR 0047 leaves Hibernate's enhancement methods out: no probe, no declaration, and a pass-through when the adopter's code calls one. Three other enhancers weave methods into the adopter's entities the same way. Without a rule, each method reads as the adopter's code, and one that never runs, such as EclipseLink's REST methods, OpenJPA's detached-state methods or the `toString` Ebean adds, is a dead-code claim the adopter cannot act on.

Each enhancer registers a transformer that cannot retransform, so the JDK runs it before the agent, and the agent sees the enhanced bytes whether the enhancement ran at build time or at load time.

Two cases go past added methods:

- **EclipseLink, property access.** The weaver puts a check into the adopter's own getter or setter, such as `if (_persistence_listener != null)`, before the method's first line-number entry. An outcome of it that never happens reads as an untaken path in the adopter's code.
- **OpenJPA, property access.** The enhancer moves the adopter's getter body to `pc<name>` (`pcgetId`), line numbers included, and puts its own wrapper, with branches and no line table, under the adopter's name. The adopter's code then reports under a name they never wrote, and a static baseline of the unenhanced class disagrees with the live agent.

## Decision

- **Gated on the enhancer's own interface.** Each rule applies only in a class whose direct interfaces include the enhancer's marker: `io.ebean.bean.EntityBean`, `org.eclipse.persistence.internal.weaving.PersistenceWeaved` or `org.apache.openjpa.enhance.PersistenceCapable`.
- **Ebean.** A member whose name starts with `_ebean_`, the constructor that takes an `EntityBean`, and a `toString()` whose body starts by constructing `io.ebean.bean.ToStringBuilder` and calls `toString(ToStringBuilder)` on itself are the enhancer's. Most are synthetic by default and already unprobed; the rule covers `toString` and an `ebean.mf` that turns the synthetic flag off.
- **EclipseLink.** A method whose name starts with `_persistence_` and has no line-number table, and the constructor that takes a `PersistenceObject`, are the enhancer's. The line-table check is needed: an adopter may write a `ChangeTracker` by hand, whose methods carry the prefix.
- **OpenJPA.** A method with no line-number table is the enhancer's when its name and descriptor are on the enhancer's fixed list (`pcGetEnhancementContractVersion`, `pcReplaceField`, `pcProvideField` and the rest), or it is a `pcGet<field>` or `pcSet<field>` accessor for a declared field that takes the owner first. The `pc` prefix alone is not enough: a hand-written `pcRate()` keeps its name.
- **Not probed, as ADR 0047 does.** These methods get no probe and no declaration. A call to one from the adopter's code is a pass-through, and an edge to one the class file on disk does not declare is dropped, as for `$$_hibernate_`.
- **EclipseLink's woven branches are generated.** In a `PersistenceWeaved` class, a branch site before a method's first line-number entry whose condition reads a `_persistence_` field or calls a `_persistence_` method carries no outcome claim. The method stays the adopter's, and its own branches are judged.
- **OpenJPA's moved accessors report under the adopter's name.** In a `PersistenceCapable` class, when a method `m` has no line-number table and `pc<m>` exists with the same descriptor, `m` is the enhancer's wrapper and a pass-through to `pc<m>`, and `pc<m>`'s probes report under the name `m`. The static baseline applies the same mapping, so a scan of the enhanced class and the live agent name the method alike.
- **OpenJPA's runtime enhancement is unsupported.** With `openjpa.RuntimeUnenhancedClasses=supported`, OpenJPA retransforms with a transformer that returns its own bytes, which discards the agent's. `docs/site/compatibility.md` says so. This is read from the source and not run.

## Considered options

- **Match by name prefix alone.** Rejected: OpenJPA's `pc` and EclipseLink's `_persistence_` both occur in code an adopter writes.
- **Mark EclipseLink's woven getters and setters whole.** Rejected: it hides the adopter's own body.
- **Leave EclipseLink's woven branches and document them.** Rejected: a known false claim.
- **Report OpenJPA's `pc<m>` under its own name.** Rejected: the wrapper's untaken branches read as the adopter's code, under a name the adopter never wrote.
- **Treat OpenJPA's property-access methods as an unread shape.** Rejected: no false claim, but it drops real findings on every property-access entity.

## Consequences

- An adopter's code compiled without line numbers loses the line-table guard. An EclipseLink `_persistence_` method or an OpenJPA list name the adopter wrote would then be left out.
- The static baseline renames OpenJPA accessors only for a class that is already enhanced on disk, which is where the mapping applies.
- Each rule is checked against an entity enhanced by each library in the test suite, as ADR 0047's is.

## Amended on 2026-10-27: settled while building

- **EclipseLink's woven checks are dropped sites.** A woven check gets no branch probe, with the drop reason `JPA_WEAVING`, counted in the first-flush INFO line. A branch probe carries its method's mark, and the method stays the adopter's. Rejected: a per-site mark, which needs a `GeneratedBy` value for an enhancer and a wire change for no gain, since a dropped site already makes no claim.
- **OpenJPA's list is longer.** It also holds `writeObject` and `readObject`, which the enhancer adds to a serializable entity that lacks them, and `readExternalFields` and `writeExternalFields`. `readExternal` and `writeExternal` count only beside `pcReadUnmanaged` and `pcWriteUnmanaged`, since the enhancer adds them together and refuses an entity that declares its own. `pcVersionInit` is a field in 4.1.1, not a method. Every entry needs no line-number table.
- **Ebean's `equals` and `hashCode`.** Ebean writes them with line numbers, and with `synthetic` off they carry no `ACC_SYNTHETIC` flag, so an `equals(Object)` or `hashCode()` that calls the class's own `_ebean_getIdentity` is the enhancer's.
- **A moved OpenJPA accessor takes its wrapper's outside caller.** The enhancer moves the adopter's runtime-visible method annotations to the wrapper, and only the wrapper overrides a supertype's method, so the moved method reports the wrapper's callback annotation and overridden type.
- **The wrapper rule needs a line table on `pc<m>`.** Without it, a class compiled without line numbers could match an adopter's own `m` and `pc<m>`.
- **Build time only for the woven checks and the rename.** The agent reads a class's shape from its class file. Under load-time enhancement that file predates the enhancer, so neither rule applies. Added methods still get no probe, since only methods the class file declares are probed. An EclipseLink-woven method then fails to pair and loses its branch probes. An OpenJPA property-access wrapper would pair with the adopter's method and report the wrong outcome, so it is left unpaired: when the received bytes carry OpenJPA's marker and declare a `pc<m>` the class file does not, the class file's `m` keeps its entry probe and gets no branch probes. The rule reads no line table, so a class compiled without line numbers is covered too.
