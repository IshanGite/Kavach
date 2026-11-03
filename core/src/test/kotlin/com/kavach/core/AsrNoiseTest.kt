package com.kavach.core

import com.kavach.core.analysis.AsrFuzzy
import com.kavach.core.analysis.KeywordAnalyst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fixtures are verbatim on-device Whisper output from a real Hinglish scam call. */
class AsrNoiseTest {
    private val realWindow = listOf(
        "kodichital arrested yaa jaaraa hai abhi abhaagom ek yuhi aad", "50.000 rupees", "...your credit card.",
        "Girdert car fraud", "award cutney gila abhi aapko ak vain aaddp a kane par aadd",
    ).joinToString(" ")

    @Test fun noisyWindowAccumulatesPastNudge() {
        val f = KeywordAnalyst.analyze(realWindow)
        assertTrue(f.llmRisk >= 60, "noisy window scored only ${f.llmRisk}")
        assertTrue(f.tactics.threatOfArrestOrLegalAction > 0 && f.tactics.urgencyOrDeadline > 0 && f.tactics.paymentOrTransferRequest > 0)
    }

    @Test fun romanisationVariantsMatch() {
        assertTrue(AsrFuzzy.scan("aap girphtar ho jaoge").containsKey(AsrFuzzy.Cat.THREAT))
        assertTrue(AsrFuzzy.scan("you are being arested").containsKey(AsrFuzzy.Cat.THREAT))
        assertTrue(AsrFuzzy.scan("immediatly").containsKey(AsrFuzzy.Cat.URGENCY))
        assertTrue(AsrFuzzy.scan("rupaiye").containsKey(AsrFuzzy.Cat.MONEY))
        assertEquals(AsrFuzzy.canon("gireftaar"), AsrFuzzy.canon("gireftar"))
    }

    @Test fun lookAlikesAndShortTokensDoNotMatch() {
        assertTrue(AsrFuzzy.scan("one year warranty on the phone").isEmpty())
        assertTrue(AsrFuzzy.scan("car card cart forest abhishek").filterKeys { it == AsrFuzzy.Cat.THREAT }.isEmpty())
        assertTrue(AsrFuzzy.scan("cart").isEmpty()) // short tokens must match exactly after normalisation
    }

    @Test fun gibberishAndEverydayNoisyTalkStayLow() {
        listOf(
            "you already have Om Soma and there it bang.com",
            "Om suma bol rahu & upka",
            "mummy abhi ghar aa rahi hai paise rupaye bhej dungi",
            "50.000 rupees ka saman hai abhi bhej do",
        ).forEach { assertTrue(KeywordAnalyst.analyze(it).llmRisk < 25, it) }
    }

    @Test fun devanagariVariantsCount() {
        val f = KeywordAnalyst.analyze("आपको अरेस्ट किया जाएगा, अभी पचास हज़ार रुपये ट्रांसफर करो")
        assertTrue(f.tactics.threatOfArrestOrLegalAction > 0 && f.tactics.paymentOrTransferRequest > 0)
    }
}
