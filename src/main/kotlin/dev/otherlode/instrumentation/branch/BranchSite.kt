package dev.otherlode.instrumentation.branch

import dev.otherlode.export.ConditionPart
import dev.otherlode.export.UnreadShape

/**
 * One tracked branch point found in a class: a two-outcome conditional jump or a
 * `TABLESWITCH`/`LOOKUPSWITCH`.
 *
 * @property siteIndex Assigned once per class by [BranchSiteAnalyzer], in the class file's encounter
 * order across all its methods.
 * @property outcomeCount A conditional jump has 2 outcomes. A switch has its case count plus one for the default.
 * @property dropReason Set when the site gets no probe at all; see [BranchDropReason].
 * @property line The source line of the site. For a kept inlined copy it is the origin's own line.
 * @property inlinedFromClassName The dotted origin class when the site is a copy of an inline
 * function's body kotlinc planted in this method, otherwise null. A dropped copy carries it too.
 * @property conditionFingerprint The site's canonical fingerprint text, built by
 * [ConditionFingerprinter], or null when the site cannot be fingerprinted with confidence.
 * @property caseKeys For a switch, one entry per case outcome, in the order [BranchProbeMethodVisitor]
 * numbers case outcomes, so `caseKeys.size + 1 == outcomeCount`.
 * Null for a conditional and for a switch [ConditionFingerprinter] could not read.
 * @property isSwitch True for a `TABLESWITCH` or `LOOKUPSWITCH`. A one-case switch also has two outcomes.
 * @property condition The expression the site tests, written by [ConditionWriter]. Empty for a
 * dropped site and for a site the writer could not write.
 * @property caseLabels For a switch [SwitchLowering] rebuilt from a string or enum lowering, one
 * label per case outcome, in the order of [caseKeys].
 * @property throwingDefault True when a rebuilt switch's default only throws an exception the
 * compiler added. The default keeps its branch index but gets no probe.
 * @property unprobedOutcome The offset of a conditional's outcome that only a hash collision can
 * reach. It is treated like a throwing default.
 * @property unreadShape Set on a kept site in a suspend-shaped method whose operand comes from a
 * continuation's `label` field or the suspended marker, in a shape [CoroutineShapes] does not read.
 * Every outcome of the site is then an unread shape of that family.
 * @property unreadOutcome The offset of the one outcome of a string switch lowering that
 * [SwitchLowering] could not read. It is an unread shape of [UnreadShape.STRING_SWITCH] and still gets its probe.
 * @property stringHashCodeSwitch True for a switch on `String.hashCode()`, so each of [caseKeys] is
 * a string's hash code.
 */
data class BranchSite(
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val siteIndex: Int,
    val outcomeCount: Int = 2,
    val dropReason: BranchDropReason? = null,
    val inlinedFromClassName: String? = null,
    val conditionFingerprint: String? = null,
    val caseKeys: List<Int>? = null,
    val isSwitch: Boolean = false,
    val condition: List<ConditionPart> = emptyList(),
    val caseLabels: List<ConditionPart>? = null,
    val throwingDefault: Boolean = false,
    val unprobedOutcome: Int? = null,
    val unreadShape: UnreadShape = UnreadShape.NONE,
    val unreadOutcome: Int? = null,
    val stringHashCodeSwitch: Boolean = false,
) {
    /** The offsets of the outcomes that get a probe, in offset order. Empty for a dropped site. */
    val probedPositions: List<Int>
        get() =
            when {
                dropReason != null -> emptyList()
                throwingDefault -> (0 until outcomeCount - 1).toList()
                else -> (0 until outcomeCount).filter { it != unprobedOutcome }
            }

    /** How many probe slots the site takes: the size of [probedPositions]. */
    val probedOutcomeCount: Int
        get() = probedPositions.size
}
