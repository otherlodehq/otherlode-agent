package dev.otherlode.instrumentation.branch

import dev.otherlode.export.BranchRole
import dev.otherlode.export.ConditionPartKind

/** The kept sites of [method] in [analysis]. */
internal fun kept(
    analysis: BranchSiteAnalyzer.Analysis,
    method: String,
): List<KeptBranchSite> = analysis.keptSites.filter { it.site.methodName == method }

/** The sites of [method] in [analysis] that carry a drop reason. */
internal fun dropped(
    analysis: BranchSiteAnalyzer.Analysis,
    method: String,
): List<BranchSite> = analysis.sites.filter { it.methodName == method && it.dropReason != null }

/** Each outcome as its role and label: `RED`, `"open"` for a literal, or `default`. */
internal fun outcomes(site: KeptBranchSite): List<String> =
    site.outcomes.map { outcome ->
        when (outcome.role) {
            BranchRole.DEFAULT -> {
                "default"
            }

            BranchRole.CASE -> {
                val label = outcome.caseLabel.single()
                if (label.kind == ConditionPartKind.STRING_LITERAL) "\"${label.text}\"" else label.text
            }

            else -> {
                outcome.role.name
            }
        }
    }

/** The one line each outcome guards whole, or null when it guards none or several. */
internal fun guardedLines(site: KeptBranchSite): List<Int?> =
    site.outcomes.map { outcome ->
        outcome.guardedLines
            .singleOrNull()
            ?.takeIf { it.firstLine == it.lastLine }
            ?.firstLine
    }
