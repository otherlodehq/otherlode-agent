package dev.otherlode.registry

import dev.otherlode.export.BranchSite
import dev.otherlode.export.CallEdge
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.OutsideCaller
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.UnreadShape

/**
 * Source-location metadata for one probe slot. A field that applies to one [ProbeKind] is empty,
 * null or false on the others.
 *
 * [inline] marks a probe in a Kotlin inline function, or a branch inside one, so a zero hit total
 * is not evidence the code never ran.
 *
 * On an [ProbeKind.OPTIONAL_ARGUMENT] probe, [methodName], [methodDescriptor] and [inline] describe
 * the target function the parameter belongs to, not the synthetic `$default` method or Scala
 * default getter the probe sits in, and [line] is the line of the parameter's default value.
 * [parameterIndex], [parameterName] and [overridable] apply only to it. [targetClassName] is set
 * only when the target is in a different class from the probe's own.
 *
 * Only a [ProbeKind.METHOD] probe sets [calls] (the in-scope call edges read from the method's own
 * bytecode), [referencedClasses] (the out-of-scope classes it references, dotted, with JDK classes
 * and classes read from a classpath directory dropped), [lambdaBody], [branchSites] (the kept
 * branch sites, in site index order), [static] (the method has `ACC_STATIC`; false for a
 * constructor and the type initializer's probe), [parameterNames], [genericSignature],
 * [extensionReceiver] and [outsideCaller]. See [dev.otherlode.export.ProbeLocation] and
 * [dev.otherlode.export.OutsideCaller].
 *
 * Only a [ProbeKind.BRANCH] probe sets [branchKey], [siteIndex] and [inlinedFromClassName].
 * [branchKey] is an opaque lowercase hex token naming the outcome across builds and instances,
 * compared only for equality, or null when the agent cannot name it safely. [inlinedFromClassName]
 * is dotted and set when the site is code kotlinc copied from an inline function whose origin class
 * is in scope.
 *
 * [generatedBy] is set for a METHOD probe, for a BRANCH probe as the mark of the method it sits in,
 * and for an OPTIONAL_ARGUMENT probe as its target's mark. See [GeneratedBy]. [unreadShape] is set
 * where [generatedBy] is, and never beside a mark: the code has the outline of compiler output but
 * matches no shape the agent has read. See [UnreadShape].
 */
data class ProbeMeta(
    val kind: ProbeKind,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val branchIndex: Int? = null,
    val inline: Boolean = false,
    val parameterIndex: Int? = null,
    val parameterName: String? = null,
    val overridable: Boolean = false,
    val targetClassName: String? = null,
    val calls: List<CallEdge> = emptyList(),
    val inlinedFromClassName: String? = null,
    val generatedBy: GeneratedBy = GeneratedBy.NONE,
    val unreadShape: UnreadShape = UnreadShape.NONE,
    val referencedClasses: List<String> = emptyList(),
    val branchKey: String? = null,
    val lambdaBody: Boolean = false,
    val branchSites: List<BranchSite> = emptyList(),
    val siteIndex: Int? = null,
    val static: Boolean = false,
    val parameterNames: List<String> = emptyList(),
    val genericSignature: String = "",
    val extensionReceiver: Boolean = false,
    val outsideCaller: OutsideCaller? = null,
)
