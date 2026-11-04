package com.kavach.core

import com.kavach.core.TestKit.at
import com.kavach.core.TestKit.pay
import com.kavach.core.TestKit.say
import com.kavach.core.TestKit.session
import com.kavach.core.TestKit.unknownCall
import com.kavach.core.analysis.TranscriptQuality
import com.kavach.core.model.CallChannel
import com.kavach.core.model.PayStage
import com.kavach.core.model.Tactics
import com.kavach.core.model.TacticsFindings
import com.kavach.core.risk.Tier
import com.kavach.core.state.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptQualityTest {
    private val deva = "अचाँ वाँ वाँ वाँ वाँ वा"
    private val papp = "PAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPP"
    private val real = "sir kaam ho gaya hai switch board change kar diya 800 rupaye hue hain QR se de dijiye"

    @Test
    fun `real speech counts, the three phone junk lines count for almost nothing`() {
        assertTrue(TranscriptQuality.cleanWordCount(real) >= 15)
        assertEquals(0, TranscriptQuality.cleanWordCount(papp))
        assertTrue(TranscriptQuality.cleanWordCount(deva) <= 3, "repeated syllable collapses: ${TranscriptQuality.cleanWords(deva)}")
        assertEquals(1, TranscriptQuality.cleanWordCount("Shrisham"))
        assertTrue(TranscriptQuality.cleanWordCount("$deva $papp Shrisham") < 12)
    }

    @Test
    fun `token spam and repeated units do not inflate the count`() {
        assertEquals(0, TranscriptQuality.cleanWordCount("Rdbjjjjjjjjj"))
        assertEquals(1, TranscriptQuality.cleanWordCount("haan haan haan haan haan"))
        assertEquals(2, TranscriptQuality.cleanWordCount("ho jayega ho jayega ho jayega"))
        assertTrue(TranscriptQuality.cleanWordCount("send 1000000 rupees now") == 4)
    }

    @Test
    fun `clean fraction is low for junk dominated text`() {
        assertTrue(TranscriptQuality.stats(real).cleanFraction > 0.9)
        assertTrue(TranscriptQuality.stats("$deva $papp Shrisham").cleanFraction < 0.6)
    }

    @Test
    fun `junk transcript with a benign finding is no evidence and cannot lower a strong VoIP payment context`() {
        val engine = TestKit.engine()
        val s = session(
            unknownCall(channel = CallChannel.VOIP), say(10, deva), say(30, papp), say(50, "Shrisham"),
            pay(70, PayStage.AMOUNT_ENTERED, 50000.0, "verification.acct@oksbi"),
        ).reduce(com.kavach.core.model.FindingsReady(at(71), TacticsFindings(llmRisk = 0, source = "keywords")), TestKit.cfg)
        val r = engine.evaluate(s)
        assertNull(r.languageScore, "L stays unknown on junk")
        assertEquals(r.contextScore, r.risk)
        assertTrue(r.tier.level >= Tier.PAUSE.level, "R=${r.risk}")
    }

    @Test
    fun `enough clean speech still lets a benign finding lower context`() {
        val engine = TestKit.engine()
        val s = session(
            unknownCall(), say(5, "the plumber will come tomorrow at five so please keep the door open and the water tank clean"),
            pay(20, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl"),
        ).reduce(com.kavach.core.model.FindingsReady(at(21), TacticsFindings(llmRisk = 0)), TestKit.cfg)
        val r = engine.evaluate(s)
        assertEquals(0, r.languageScore)
        assertTrue(r.risk < r.contextScore)
    }

    @Test
    fun `thin transcript with a real lexicon hit still counts but never lowers context`() {
        val engine = TestKit.engine()
        val s = session(
            unknownCall(), say(5, "police arrest warrant now do it right away please"), pay(20, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl"),
        ).reduce(
            com.kavach.core.model.FindingsReady(at(21), TacticsFindings(tactics = Tactics(threatOfArrestOrLegalAction = 0.65), llmRisk = 5)),
            TestKit.cfg,
        )
        val r = engine.evaluate(s)
        assertTrue(r.risk >= r.contextScore)
    }
}
