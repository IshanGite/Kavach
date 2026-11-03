package com.kavach.app.ui

import android.view.WindowManager
import com.kavach.app.engine.ScamNotice
import com.kavach.app.engine.TranscriptLine
import com.kavach.app.engine.WidgetSurface
import com.kavach.app.engine.noticeFrom
import com.kavach.app.engine.pushNotice
import com.kavach.core.intervention.Lang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetMapperTest {
    @Test
    fun `normal life stays a calm collapsed bubble`() {
        val view = WidgetMapper.project(WidgetSurface())
        assertEquals(WidgetCard.BUBBLE, view.card)
        assertEquals(WidgetTierBand.SAFE, view.tier)
        assertEquals("Safe", view.tierLabel)
        assertNull(view.callerTitle)
        assertTrue(view.reasons.isEmpty())
        assertTrue(view.advice.isEmpty())
        assertFalse(view.consumesTouchesOutside)
    }

    @Test
    fun `an unknown call expands even while the tier is still safe`() {
        val view = WidgetMapper.project(unknownCall(), nowMs = 16_000, familyName = "Anjali")
        assertEquals(WidgetCard.LIVE, view.card)
        assertEquals(WidgetTierBand.SAFE, view.tier)
        assertEquals("Unknown caller", view.callerTitle)
        assertEquals("+91 99887 76655", view.callerSubtitle)
        assertEquals(15L, view.callElapsedSec)
        assertEquals("0:15", formatCallTimer(view.callElapsedSec!!))
        assertEquals("Listening for speech", view.listeningHint)
        assertEquals("Call 1930", view.helplineLabel)
        assertEquals("Call Anjali", view.familyLabel)
        assertFalse(view.consumesTouchesOutside)
    }

    @Test
    fun `a missing caller number reads as unknown caller`() {
        val view = WidgetMapper.project(unknownCall(number = ""))
        assertEquals("Unknown caller", view.callerTitle)
        assertNull(view.callerSubtitle)
        val hindi = WidgetMapper.project(unknownCall(number = "private"), lang = Lang.HI)
        assertEquals("अज्ञात कॉलर", hindi.callerTitle)
        assertEquals("निगरानी", WidgetMapper.project(unknownCall(tier = 1), lang = Lang.HI).tierLabel)
    }

    @Test
    fun `a saved contact stays collapsed until the tier rises`() {
        val calm = WidgetMapper.project(savedContact(tier = 0))
        assertEquals(WidgetCard.BUBBLE, calm.card)
        assertEquals(WidgetTierBand.SAFE, calm.tier)

        val risen = WidgetMapper.project(
            savedContact(tier = 1).copy(contextScore = 40, languageScore = 55, risk = 46, reasons = listOf("Caller claims to be police")),
        )
        assertEquals(WidgetCard.LIVE, risen.card)
        assertEquals(WidgetTierBand.WATCHING, risen.tier)
        assertEquals("Anjali", risen.callerTitle)
        assertEquals(listOf("Caller claims to be police"), risen.reasons)
        assertEquals(40, risen.contextScore)
        assertEquals(55, risen.languageScore)
        assertEquals(46, risen.risk)
    }

    @Test
    fun `the mapper displays the engine tier and does not recompute it from the scores`() {
        val safe = WidgetMapper.project(WidgetSurface(tierLevel = 0, risk = 99, contextScore = 99, languageScore = 99))
        assertEquals(WidgetTierBand.SAFE, safe.tier)
        assertEquals(WidgetCard.BUBBLE, safe.card)
        assertEquals(99, safe.risk)

        val caution = WidgetMapper.project(WidgetSurface(tierLevel = 2, risk = 10, contextScore = 10))
        assertEquals(WidgetTierBand.CAUTION, caution.tier)
        assertEquals(WidgetCard.LIVE, caution.card)
        assertEquals(WidgetTierBand.DANGER, WidgetMapper.project(WidgetSurface(tierLevel = 3)).tier)
    }

    @Test
    fun `collapse holds until the tier rises or a new scam alert arrives`() {
        val call = unknownCall(tier = 0)
        var latch = WidgetMapper.collapse(WidgetUiLatch(), call)
        assertEquals(WidgetCard.BUBBLE, WidgetMapper.project(call, latch).card)

        latch = WidgetMapper.bubbleTap(latch, call)
        assertEquals(WidgetCard.LIVE, WidgetMapper.project(call, latch).card)

        latch = WidgetMapper.collapse(latch, call)
        val risen = call.copy(tierLevel = 1, risk = 40, contextScore = 40)
        assertEquals(WidgetCard.LIVE, WidgetMapper.project(risen, latch).card)

        latch = WidgetMapper.collapse(latch, risen)
        assertEquals(WidgetCard.BUBBLE, WidgetMapper.project(risen, latch).card)
        val withAlert = risen.copy(scamNotices = listOf(kyc("m1")))
        val expanded = WidgetMapper.project(withAlert, latch)
        assertEquals(WidgetCard.LIVE, expanded.card)
        assertNotNull(expanded.footnote)
        assertTrue(expanded.footnote.contains("KYC"))
    }

    @Test
    fun `dismiss does not restore the same incident and a tier rise does`() {
        val call = unknownCall(tier = 1, risk = 40)
        val latch = WidgetMapper.dismiss(WidgetUiLatch(), call)
        assertEquals(WidgetCard.BUBBLE, WidgetMapper.project(call, latch).card)
        assertEquals(WidgetCard.BUBBLE, WidgetMapper.project(call, WidgetMapper.bubbleTap(latch, call)).card)

        val higher = call.copy(tierLevel = 2, risk = 70)
        assertEquals(WidgetCard.LIVE, WidgetMapper.project(higher, latch).card)
        assertEquals(WidgetTierBand.CAUTION, WidgetMapper.project(higher, latch).tier)
    }

    @Test
    fun `a scam notification expands with advice and stays in history after dismiss`() {
        val first = kyc("a")
        val surface = WidgetSurface(scamNotices = listOf(first))
        val shown = WidgetMapper.project(surface, familyName = "Anjali")
        assertEquals(WidgetCard.ALERT, shown.card)
        assertEquals("WhatsApp", shown.alertSource)
        assertEquals("KYC/account block threat", shown.alertWhy)
        assertTrue(shown.advice.any { it.contains("click", ignoreCase = true) })
        assertTrue(shown.advice.any { it.contains("PIN") })
        assertTrue(shown.advice.any { it.contains("1930") })
        assertEquals(1, shown.alertCount)
        assertFalse(shown.consumesTouchesOutside)

        val dismissed = WidgetMapper.dismiss(WidgetUiLatch(), surface)
        val calm = WidgetMapper.project(surface, dismissed)
        assertEquals(WidgetCard.BUBBLE, calm.card)
        assertEquals(listOf("a"), calm.recentAlerts.map { it.id })

        val second = arrest("b")
        val both = surface.copy(scamNotices = listOf(second, first))
        val again = WidgetMapper.project(both, dismissed)
        assertEquals(WidgetCard.ALERT, again.card)
        assertEquals("b", again.recentAlerts.first().id)
        assertEquals(listOf("b", "a"), again.recentAlerts.map { it.id })

        val bothGone = WidgetMapper.dismiss(dismissed, both)
        val historyLatch = WidgetMapper.openHistory(bothGone, both)
        val history = WidgetMapper.project(both, historyLatch)
        assertEquals(WidgetCard.HISTORY, history.card)
        assertEquals(2, history.recentAlerts.size)
        assertTrue(history.advice.any { it.contains("1930") })
        assertEquals(WidgetCard.BUBBLE, WidgetMapper.project(both, WidgetMapper.closeHistory(historyLatch)).card)
    }

    @Test
    fun `a newer alert closes an open history list`() {
        val older = WidgetSurface(scamNotices = listOf(kyc("a")))
        val open = WidgetMapper.openHistory(WidgetUiLatch(), older)
        assertEquals(WidgetCard.HISTORY, WidgetMapper.project(older, open).card)
        val newer = older.copy(scamNotices = listOf(arrest("b"), kyc("a")))
        assertEquals(WidgetCard.ALERT, WidgetMapper.project(newer, open).card)
    }

    @Test
    fun `history keeps the newest alerts and drops the oldest past eight`() {
        var list = emptyList<ScamNotice>()
        for (i in 1..9) list = pushNotice(list, kyc("id$i").copy(atMs = i.toLong()))
        assertEquals(8, list.size)
        assertEquals("id9", list.first().id)
        assertEquals("id2", list.last().id)
        assertEquals(list, pushNotice(list, kyc("id9")))
        val view = WidgetMapper.project(WidgetSurface(scamNotices = list))
        assertEquals(8, view.recentAlerts.size)
        assertEquals("id9", view.recentAlerts.first().id)
    }

    @Test
    fun `otp and low-confidence messages do not become alerts`() {
        assertNull(noticeFrom(1, "VM-HDFCBK", "482913 is your OTP, do not share", false, 0.9, "genuine-looking OTP message", "NONE"))
        assertNull(noticeFrom(2, "x", "maybe a scam", true, 0.49, "weak", "KYC_BANK"))
        val kept = noticeFrom(3, " WhatsApp ", "  Your   KYC will expire  ", true, 0.5, "KYC/account block threat", "KYC_BANK")
        assertNotNull(kept)
        assertEquals("WhatsApp", kept.source)
        assertEquals("Your KYC will expire", kept.excerpt)
        assertTrue(kept.id.contains("WhatsApp"))
    }

    @Test
    fun `transcript shows the last three lines and marks scam phrases`() {
        val lines = (1..5).map { TranscriptLine("line $it about the parcel", it.toLong()) } +
            TranscriptLine("You will be arrested. Don't tell anyone.", 6)
        val view = WidgetMapper.project(
            unknownCall().copy(
                transcript = lines,
                evidenceQuotes = listOf("arrested", "Don't tell anyone", "a"),
                detectedLanguage = "<|hi|>",
            ),
        )
        assertEquals(3, view.transcript.size)
        assertEquals("line 4 about the parcel", view.transcript[0].text)
        assertEquals("Hindi", view.languageLabel)
        val marked = view.transcript.last()
        assertTrue(marked.spans.isNotEmpty(), "scam phrases should be marked")
        assertTrue(marked.spans.all { it.start < it.endExclusive && it.endExclusive <= marked.text.length })
        val painted = marked.spans.joinToString("|") { marked.text.substring(it.start, it.endExclusive) }
        assertTrue(painted.contains("arrested", ignoreCase = true))
        assertTrue(painted.contains("Don't tell anyone", ignoreCase = true))
        assertFalse(painted.contains(" a "))
        assertNull(WidgetMapper.project(unknownCall().copy(detectedLanguage = "auto")).languageLabel)
    }

    @Test
    fun `hindi copy covers the tier, the advice and the caller`() {
        val view = WidgetMapper.project(
            WidgetSurface(scamNotices = listOf(kyc("a"))),
            lang = Lang.HI,
            familyName = "Anjali",
        )
        assertEquals("ठगी जैसा संदेश", view.alertTitle)
        assertEquals("केवाईसी या अकाउंट बंद करने की धमकी", view.alertWhy)
        assertEquals("1930 पर कॉल", view.helplineLabel)
        assertEquals("Anjali को कॉल", view.familyLabel)
        assertEquals("बंद करें", view.dismissLabel)
        assertEquals("छोटा करें", view.collapseLabel)
        assertTrue(view.advice.any { it.contains("क्लिक") })
        assertTrue(view.advice.any { it.contains("पैसे") })
        assertTrue(view.advice.any { it.contains("1930") })
        assertEquals("खतरा", WidgetMapper.project(WidgetSurface(tierLevel = 3), lang = Lang.HI).tierLabel)
    }

    @Test
    fun `a full-screen pause keeps the widget collapsed to a bubble`() {
        val hot = unknownCall(tier = 3, risk = 90).copy(contextScore = 80, languageScore = 95)
        val open = WidgetMapper.project(hot, fullScreenPauseShowing = false)
        assertEquals(WidgetCard.LIVE, open.card)
        assertEquals(WidgetTierBand.DANGER, open.tier)
        val covered = WidgetMapper.project(hot, fullScreenPauseShowing = true)
        assertEquals(WidgetCard.BUBBLE, covered.card)
        assertEquals(WidgetTierBand.DANGER, covered.tier)
        assertEquals(90, covered.risk)
        assertFalse(covered.consumesTouchesOutside)
        assertEquals(WidgetUiLatch(), WidgetMapper.bubbleTap(WidgetUiLatch(), hot, fullScreenPauseShowing = true))
    }

    @Test
    fun `the widget never consumes touches outside itself`() {
        val situations = listOf(
            WidgetSurface(),
            unknownCall(),
            unknownCall(tier = 2, risk = 70),
            WidgetSurface(scamNotices = listOf(kyc("a"), arrest("b"))),
            savedContact(tier = 1),
        )
        for (surface in situations) {
            val latches = listOf(
                WidgetUiLatch(),
                WidgetMapper.collapse(WidgetUiLatch(), surface),
                WidgetMapper.dismiss(WidgetUiLatch(), surface),
                WidgetMapper.openHistory(WidgetUiLatch(), surface),
            )
            for (latch in latches) {
                val view = WidgetMapper.project(surface, latch)
                assertFalse(view.consumesTouchesOutside, "${view.card} ${surface.tierLevel}")
            }
            assertFalse(WidgetMapper.project(surface, fullScreenPauseShowing = true).consumesTouchesOutside)
        }
        assertFalse(WidgetWindowPolicy.consumesTouchesOutside())
    }

    @Test
    fun `window flags are not-focusable and not-touch-modal and the window is never full screen`() {
        val flags = WidgetWindowPolicy.touchFlags()
        assertEquals(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WidgetWindowPolicy.FLAG_NOT_FOCUSABLE)
        assertEquals(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, WidgetWindowPolicy.FLAG_NOT_TOUCH_MODAL)
        assertEquals(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WidgetWindowPolicy.FLAG_NOT_TOUCHABLE)
        assertEquals(WidgetWindowPolicy.FLAG_NOT_FOCUSABLE, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(WidgetWindowPolicy.FLAG_NOT_TOUCH_MODAL, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertFalse(WidgetWindowPolicy.consumesTouchesOutside())
        WidgetCard.entries.forEach { card ->
            val (w, h) = WidgetWindowPolicy.layoutSizeDp(card)
            assertTrue(w in 48..360, "$card width $w")
            assertTrue(h in 48..480, "$card height $h")
            assertTrue(w != WindowManager.LayoutParams.MATCH_PARENT)
            assertTrue(h != WindowManager.LayoutParams.MATCH_PARENT)
        }
    }

    @Test
    fun `the bubble is shown only while protection and the setting are on`() {
        assertTrue(WidgetMapper.shouldShow(protectionOn = true, widgetEnabled = true))
        assertFalse(WidgetMapper.shouldShow(protectionOn = true, widgetEnabled = false))
        assertFalse(WidgetMapper.shouldShow(protectionOn = false, widgetEnabled = true))
        assertFalse(WidgetMapper.shouldShow(protectionOn = false, widgetEnabled = false))
    }

    private fun unknownCall(
        tier: Int = 0,
        number: String = "+91 99887 76655",
        risk: Int = 20,
    ) = WidgetSurface(
        atMs = 16_000,
        callLive = true,
        callerNumber = number,
        callerInContacts = false,
        callStartedAtMs = 1_000,
        tierLevel = tier,
        contextScore = risk,
        risk = risk,
    )

    private fun savedContact(tier: Int) = WidgetSurface(
        atMs = 5_000,
        callLive = true,
        callerNumber = "+919800000001",
        callerInContacts = true,
        callerName = "Anjali",
        callStartedAtMs = 1_000,
        tierLevel = tier,
    )

    private fun kyc(id: String) = ScamNotice(
        id = id,
        atMs = 1,
        source = "WhatsApp",
        excerpt = "Your KYC will expire, account blocked",
        why = "KYC/account block threat",
        typeName = "KYC_BANK",
    )

    private fun arrest(id: String) = ScamNotice(
        id = id,
        atMs = 2,
        source = "SMS",
        excerpt = "Arrest warrant attached",
        why = "mentions arrest warrant / legal notice",
        typeName = "DIGITAL_ARREST",
    )
}
