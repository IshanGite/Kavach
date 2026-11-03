package com.kavach.app.ui

import com.kavach.core.intervention.Lang
import com.kavach.core.risk.RuleCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskLabelsTest {
    @Test fun situationHelperUsesRealRuleHits() {
        val s = RiskLabels.helper(RiskLabels.Bar.SITUATION, Lang.EN, listOf(RuleCode.UNKNOWN_LIVE_CALL, RuleCode.PAYMENT_APP_DURING_CALL))
        assertEquals("Unknown caller + payment app open", s)
    }

    @Test fun genericWhenNoHits() {
        assertTrue(RiskLabels.helper(RiskLabels.Bar.SITUATION, Lang.EN).contains("new payee"))
        assertTrue(RiskLabels.helper(RiskLabels.Bar.SAYING, Lang.EN).contains("urgency"))
    }

    @Test fun tierWordsAndNumbers() {
        assertEquals(listOf("Safe", "Watching", "Caution", "Danger"), (0..3).map { RiskLabels.tierWord(it, Lang.EN) })
        assertEquals("Situation 25", RiskLabels.withNumber(RiskLabels.Bar.SITUATION, 25, Lang.EN))
        assertEquals("Scam risk –", RiskLabels.withNumber(RiskLabels.Bar.RISK, null, Lang.EN))
    }

    @Test fun hindiDiffersFromEnglish() {
        for (b in RiskLabels.Bar.values()) assertTrue(RiskLabels.name(b, Lang.HI) != RiskLabels.name(b, Lang.EN))
        assertEquals("ख़तरा", RiskLabels.tierWord(3, Lang.HI))
    }
}
