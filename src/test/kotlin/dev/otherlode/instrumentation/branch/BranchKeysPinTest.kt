package dev.otherlode.instrumentation.branch

import dev.otherlode.export.ConditionPart
import dev.otherlode.export.ConditionPartKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the exact key text, since consumers join branch and site keys across builds (ADR 0031). */
class BranchKeysPinTest {
    private val conditional =
        BranchSite("check", "(I)Z", line = 3, siteIndex = 0, conditionFingerprint = "if:x>0")
    private val switch =
        BranchSite(
            "pick",
            "(I)I",
            line = 9,
            siteIndex = 1,
            outcomeCount = 3,
            isSwitch = true,
            conditionFingerprint = "switch:n",
            caseKeys = listOf(1, 7),
        )
    private val rebuilt =
        BranchSite(
            "label",
            "(Ljava/lang/String;)I",
            line = 12,
            siteIndex = 2,
            outcomeCount = 3,
            isSwitch = true,
            conditionFingerprint = "switch:s",
            caseLabels =
                listOf(
                    ConditionPart(ConditionPartKind.STRING_LITERAL, "a"),
                    ConditionPart(ConditionPartKind.CODE, "B"),
                ),
        )
    private val inlined =
        BranchSite(
            "each",
            "()V",
            line = 4,
            siteIndex = 3,
            inlinedFromClassName = "kotlin.collections.CollectionsKt",
            conditionFingerprint = "if:hasNext",
        )
    private val nonAscii =
        BranchSite("greet", "()Z", line = 5, siteIndex = 4, conditionFingerprint = "eq:\"grüße ☃ 😀\"")
    private val sites = listOf(conditional, switch, rebuilt, inlined, nonAscii)

    @Test
    fun `branch keys keep their exact text`() {
        val keys = BranchKeys.compute(sites, "com.example.Pin")
        assertEquals(EXPECTED_BRANCH, keys.toSortedMap(compareBy({ it.first }, { it.second })).toString())
    }

    @Test
    fun `site keys keep their exact text`() {
        val keys = BranchKeys.computeSiteKeys(sites, "com.example.Pin")
        assertEquals(EXPECTED_SITE, keys.toSortedMap().toString())
    }

    private companion object {
        const val EXPECTED_BRANCH =
            "{(0, 0)=da2edb0ab55763788a9ff0de23641474, (0, 1)=f0948489f88ca07b02caab4f61e59bbd, " +
                "(1, 0)=4e066f1ce8cb46a80f06effef4db74aa, (1, 1)=216a444dc18d36422466de93839736ec, " +
                "(1, 2)=58d1dca2b3364653184663f36e430817, (2, 0)=e0640eabc264f86110ca14d909dd2d68, " +
                "(2, 1)=abff553e1a5f8392eaece198683865f8, (2, 2)=16aba958a76883580e49f7cf08e0429d, " +
                "(3, 0)=86f15ba41f7ffd775975d08622786573, (3, 1)=3a0d8c6d627725890aebedd94c1583d0, " +
                "(4, 0)=0bb4ce91dd3d3e8dac6135b71b2b8790, (4, 1)=b4e383dab847e7429b0d7bfe18aa8de0}"
        const val EXPECTED_SITE =
            "{0=4155e562bc32b545b196727f1c96969c, 1=1432051fd7590d75e959bde4cfc97087, " +
                "2=3762e10643311d971ddc1923a5cf8d37, 3=b08e821132a2aed8b57c64f37895a372, " +
                "4=ec2c73dd8a8549463edb1dbf93e39143}"
    }
}
