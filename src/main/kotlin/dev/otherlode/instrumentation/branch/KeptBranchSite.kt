package dev.otherlode.instrumentation.branch

import dev.otherlode.export.BranchOutcome
import dev.otherlode.export.BranchRole
import dev.otherlode.export.ConditionPart
import dev.otherlode.export.LineRange
import dev.otherlode.export.RoutineKind
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.interned
import dev.otherlode.instrumentation.rightSized
import dev.otherlode.export.BranchSite as BranchSitePayload

/**
 * One outcome of a [KeptBranchSite]: its class-wide [branchIndex], its [role] in the site, the
 * [caseKey] of a [BranchRole.CASE] when the switch's case keys are known, and its [branchKey] when
 * one can be named. [guardedLines] and [partlyGuardedLines] are its guarded code.
 *
 * [caseLabel] is the source's label for a [BranchRole.CASE] of a rebuilt switch, and empty
 * otherwise. Such a case has no [caseKey].
 *
 * [routine] is the outcome's routine kind, or [RoutineKind.NONE]. [unreadShape] says the outcome
 * sits in compiler output the agent does not read, or is [UnreadShape.NONE]. [routine] is
 * [RoutineKind.NONE] whenever [unreadShape] is set.
 */
data class KeptBranchOutcome(
    val branchIndex: Int,
    val role: BranchRole,
    val caseKey: Int?,
    val branchKey: String?,
    val guardedLines: List<LineRange> = emptyList(),
    val partlyGuardedLines: List<LineRange> = emptyList(),
    val caseLabel: List<ConditionPart> = emptyList(),
    val routine: RoutineKind = RoutineKind.NONE,
    val unreadShape: UnreadShape = UnreadShape.NONE,
)

/**
 * One kept branch site of a class, with its [outcomes] numbered and keyed. [siteKey] is null
 * exactly when every outcome's [KeptBranchOutcome.branchKey] is null. [guard] is the branch index
 * of the innermost kept outcome that dominates the site, or null.
 */
data class KeptBranchSite(
    val site: BranchSite,
    val siteKey: String?,
    val outcomes: List<KeptBranchOutcome>,
    val guard: Int? = null,
) {
    fun toPayload(): BranchSitePayload =
        BranchSitePayload(
            siteIndex = site.siteIndex,
            siteKey = siteKey,
            line = site.line,
            outcomes =
                outcomes
                    .map {
                        BranchOutcome(
                            it.branchIndex,
                            it.role,
                            it.caseKey,
                            it.guardedLines.compactRanges(),
                            it.partlyGuardedLines.compactRanges(),
                            it.caseLabel.rightSized(),
                            it.routine,
                            it.unreadShape,
                        )
                    }.rightSized(),
            guard = guard,
            condition = site.condition.rightSized(),
            stringHashCodeSwitch = site.stringHashCodeSwitch,
        )

    private fun List<LineRange>.compactRanges(): List<LineRange> =
        when (size) {
            0 -> emptyList()
            1 -> listOf(this[0].copy(sourceFile = this[0].sourceFile.interned()))
            else -> map { it.copy(sourceFile = it.sourceFile.interned()) }
        }

    companion object {
        internal fun of(
            sites: List<BranchSite>,
            className: String,
            guards: Map<Int, SiteGuards> = emptyMap(),
            reportedName: (name: String, descriptor: String) -> String = { name, _ -> name },
        ): List<KeptBranchSite> {
            val branchKeys = BranchKeys.compute(sites, className, reportedName)
            val siteKeys = BranchKeys.computeSiteKeys(sites, className, reportedName)
            val firstBranchIndexes = firstBranchIndexes(sites)
            val kept = mutableListOf<KeptBranchSite>()
            for ((position, site) in sites.withIndex()) {
                if (site.dropReason != null) continue
                val siteGuards = guards[site.siteIndex]
                val roles = rolesOf(site)
                val outcomes =
                    site.probedPositions.mapIndexed { probed, offset ->
                        val role = roles[offset]
                        val unread =
                            when {
                                site.unreadShape != UnreadShape.NONE -> site.unreadShape
                                offset == site.unreadOutcome -> UnreadShape.STRING_SWITCH
                                else -> UnreadShape.NONE
                            }
                        val routine =
                            if (unread ==
                                UnreadShape.NONE
                            ) {
                                siteGuards?.routineKinds?.getOrNull(probed) ?: RoutineKind.NONE
                            } else {
                                RoutineKind.NONE
                            }
                        KeptBranchOutcome(
                            firstBranchIndexes[position] + offset,
                            role.role,
                            role.caseKey,
                            branchKeys[site.siteIndex to offset],
                            siteGuards?.guardedLines?.getOrNull(probed).orEmpty(),
                            siteGuards?.partlyGuardedLines?.getOrNull(probed).orEmpty(),
                            role.caseLabel,
                            routine,
                            unread,
                        )
                    }
                kept += KeptBranchSite(site, siteKeys[site.siteIndex], outcomes, siteGuards?.guard)
            }
            return kept
        }

        internal fun firstBranchIndexes(sites: List<BranchSite>): IntArray {
            val result = IntArray(sites.size)
            var next = 0
            for ((position, site) in sites.withIndex()) {
                result[position] = next
                next += site.outcomeCount
            }
            return result
        }

        private class Role(
            val role: BranchRole,
            val caseKey: Int? = null,
            val caseLabel: List<ConditionPart> = emptyList(),
        )

        private fun rolesOf(site: BranchSite): List<Role> {
            val caseKeys = site.caseKeys
            if (!site.isSwitch && caseKeys == null) return listOf(Role(BranchRole.TAKEN), Role(BranchRole.FALL_THROUGH))
            val caseCount = site.outcomeCount - 1
            val labels = site.caseLabels?.takeIf { it.size == caseCount }
            val knownKeys = caseKeys?.takeIf { it.size == caseCount && labels == null }
            val cases = List(caseCount) { Role(BranchRole.CASE, knownKeys?.get(it), listOfNotNull(labels?.get(it))) }
            return cases + Role(BranchRole.DEFAULT)
        }
    }
}
