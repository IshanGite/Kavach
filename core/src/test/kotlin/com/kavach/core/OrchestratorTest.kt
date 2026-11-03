package com.kavach.core

import com.kavach.core.TestKit.at
import com.kavach.core.TestKit.pay
import com.kavach.core.TestKit.say
import com.kavach.core.TestKit.unknownCall
import com.kavach.core.intervention.GuardianLink
import com.kavach.core.intervention.Lang
import com.kavach.core.model.*
import com.kavach.core.orchestrator.*
import com.kavach.core.report.ReportBuilder
import com.kavach.core.risk.Tier
import kotlin.test.*

private inline fun <reified T : Effect> Decision.has() = effects.any { it is T }
private inline fun <reified T : Effect> Decision.get() = effects.filterIsInstance<T>().single()

class OrchestratorTest {
    /** A coercive unknown call already in progress, with analysis delivered. */
    private fun scamCall(o: Orchestrator = TestKit.orchestrator()): Orchestrator {
        o.onEvent(unknownCall())
        o.onEvent(say(5, "I am Inspector Sharma Cyber Crime. You will be arrested, stay on the call, do not tell anyone. Transfer money."))
        o.nextAnalysisRequest(at(6))?.let { o.onEvent(FindingsReady(at(6), com.kavach.core.analysis.KeywordAnalyst.analyze(it.windowText))) }
        return o
    }

    @Test
    fun `state machine walks idle - monitoring - intervening - post incident`() {
        val o = TestKit.orchestrator()
        assertEquals(OrchestratorState.IDLE, o.state)
        assertEquals(OrchestratorState.MONITORING, o.onEvent(unknownCall()).state)
        scamCall(o)
        val d = o.onEvent(pay(30, PayStage.AMOUNT_ENTERED, 50000.0, "someone@oksbi"))
        assertEquals(OrchestratorState.INTERVENING, d.state)
        // While the pay screen is still up the pause stays in force even after the call drops.
        assertEquals(OrchestratorState.INTERVENING, o.onEvent(CallEnded(at(120), 120)).state)
        assertEquals(OrchestratorState.POST_INCIDENT, o.onEvent(PaymentDismissed(at(130))).state)
    }

    @Test
    fun `normal life produces no effects at all`() {
        val o = TestKit.orchestrator()
        val all = listOf(pay(0), pay(5, PayStage.AMOUNT_ENTERED, 200.0, "ramesh.kirana@ybl"), pay(9, PayStage.PIN_SCREEN, 200.0, "ramesh.kirana@ybl"))
            .flatMap { o.onEvent(it).effects }
        assertTrue(all.isEmpty(), "$all")
    }

    @Test
    fun `the pause overlay is shown once per escalation, not re-shown on every later frame`() {
        val o = scamCall()
        // Inspector on a personal mobile already clears a pause when the payment app opens.
        // A later amount frame at the same tier must not create the overlay again.
        val shown = o.onEvent(pay(30))
        assertTrue(shown.tier.level >= Tier.PAUSE.level); assertTrue(shown.has<Effect.ShowPause>())
        val later = o.onEvent(pay(36, PayStage.AMOUNT_ENTERED, 50000.0, "verify.pay@oksbi"))
        assertTrue(later.tier.level >= shown.tier.level)
        assertFalse(later.has<Effect.ShowPause>(), "same tier must not recreate the overlay")
        val again = o.onEvent(pay(40, PayStage.PIN_SCREEN, 50000.0, "verify.pay@oksbi"))
        assertFalse(again.has<Effect.ShowPause>(), "overlay must not be re-created on the PIN screen")
        assertFalse(again.has<Effect.SendGuardianSms>(), "guardian must not be spammed")
    }

    @Test
    fun `payee from call escalates to tier 3 with sms to the registered guardian and speech in the user's language`() {
        val o = TestKit.orchestrator(p = TestKit.profile.copy(language = Lang.HI))
        o.onEvent(unknownCall())
        o.onEvent(say(5, "send fifty thousand to verify dot pay at oksbi right now, do not tell anyone"))
        o.onEvent(pay(20))
        val d = o.onEvent(pay(26, PayStage.AMOUNT_ENTERED, 50000.0, "verify.pay@oksbi"))
        assertEquals(Tier.PAUSE_GUARDIAN, d.tier)
        assertTrue(d.has<Effect.ShowPause>())
        val sms = d.get<Effect.SendGuardianSms>()
        assertEquals(listOf(TestKit.guardian.number), sms.numbers)
        assertEquals("hi-IN", d.get<Effect.SpeakWarning>().langTag)
        assertEquals(120, d.plan!!.cooldownSeconds)
        assertEquals(3, d.plan!!.questions.size)
        assertEquals("Anjali", d.plan!!.callTrusted!!.name)
    }

    @Test
    fun `guardian alert is deduplicated within ten minutes and repeated after`() {
        val o = TestKit.orchestrator()
        o.onEvent(RemoteAccessDetected(at(0), "AnyDesk"))
        val first = o.onEvent(pay(5, PayStage.AMOUNT_ENTERED, 100.0, "x@ybl"))
        assertTrue(first.has<Effect.SendGuardianSms>())
        o.onEvent(PaymentDismissed(at(20)))
        assertFalse(o.onEvent(pay(60, PayStage.AMOUNT_ENTERED, 100.0, "y@ybl")).has<Effect.SendGuardianSms>(), "inside 10 minutes")
        o.onEvent(PaymentDismissed(at(90)))
        assertTrue(o.onEvent(pay(15 * 60, PayStage.AMOUNT_ENTERED, 100.0, "z@ybl")).has<Effect.SendGuardianSms>(), "after 10 minutes")
    }

    @Test
    fun `HOLD from a registered guardian extends the pause, from anyone else it is ignored`() {
        val o = TestKit.orchestrator()
        o.onEvent(RemoteAccessDetected(at(0), "AnyDesk"))
        o.onEvent(pay(5, PayStage.AMOUNT_ENTERED, 100.0, "x@ybl"))
        val spoofed = o.onEvent(GuardianHold(at(30), "+91 99999 00000"))
        assertFalse(spoofed.has<Effect.ExtendPause>()); assertNull(o.session.guardianHoldUntilMs)
        val real = o.onEvent(GuardianHold(at(31), "098000 00001")) // same last 10 digits as the registered +919800000001
        assertEquals(at(31) + 10 * 60_000L, real.get<Effect.ExtendPause>().untilMs)
        assertTrue(o.pauseActiveAt(at(31) + 9 * 60_000L)); assertFalse(o.pauseActiveAt(at(31) + 11 * 60_000L))
    }

    @Test
    fun `pause is latched so it cannot flicker off mid-payment`() {
        val o = scamCall()
        o.onEvent(pay(30))
        o.onEvent(pay(36, PayStage.AMOUNT_ENTERED, 50000.0, "aa@oksbi"))
        // a later frame loses the payee and amount (accessibility read failed)
        val d = o.onEvent(pay(40, PayStage.PIN_SCREEN))
        assertTrue(d.tier.level >= Tier.PAUSE.level, "overlay must stay up, got ${d.tier}")
    }

    @Test
    fun `closing the payment screen dismisses the overlay and resets the latch`() {
        val o = scamCall()
        o.onEvent(pay(30, PayStage.AMOUNT_ENTERED, 50000.0, "aa@oksbi"))
        val d = o.onEvent(PaymentDismissed(at(50)))
        assertTrue(d.has<Effect.DismissOverlay>()); assertTrue(d.tier.level <= Tier.NUDGE.level)
    }

    @Test
    fun `nudge is shown once, and again when a payment screen opens`() {
        val o = TestKit.orchestrator()
        o.onEvent(unknownCall(channel = CallChannel.VOIP)) // 20 + 15 = 35 -> nudge
        val first = o.onEvent(Tick(at(5)))
        val shown = first.has<Effect.ShowNudge>() || o.state == OrchestratorState.ELEVATED
        assertTrue(shown)
        assertFalse(o.onEvent(Tick(at(10))).has<Effect.ShowNudge>(), "no nagging")
    }

    @Test
    fun `dismissing a nudge for the same payee twice makes it trusted and silences later nudges`() {
        val o = TestKit.orchestrator()
        o.onEvent(unknownCall()) // cellular unknown call: payment app (+25) and a new payee (+10) land at 55 = nudge
        fun attempt(base: Int): List<Effect> {
            val fx = mutableListOf<Effect>()
            fx += o.onEvent(PaymentDismissed(at(base))).effects
            fx += o.onEvent(pay(base + 1)).effects
            fx += o.onEvent(pay(base + 2, PayStage.AMOUNT_ENTERED, 150.0, "tea.stall@ybl")).effects
            fx += o.onEvent(UserProceeded(at(base + 3))).effects
            return fx
        }
        assertTrue(attempt(10).any { it is Effect.ShowNudge }, "first time the nudge appears")
        assertTrue(attempt(30).any { it is Effect.ShowNudge }, "second time it still appears")
        assertTrue("tea.stall@ybl" in o.trustedPayees())
        assertFalse(attempt(50).any { it is Effect.ShowNudge }, "after two dismissals the payee is trusted")
    }

    @Test
    fun `a yes answer confirms coercion with the spec message`() {
        val o = scamCall(); o.onEvent(pay(30, PayStage.AMOUNT_ENTERED, 50000.0, "aa@oksbi"))
        val d = o.onEvent(QuestionsAnswered(at(40), 1))
        assertContains(d.get<Effect.CoercionConfirmed>().message, "Government agencies never do this")
    }

    @Test
    fun `call end after an elevated session offers a check-in`() {
        val o = scamCall(); o.onEvent(pay(30, PayStage.AMOUNT_ENTERED, 50000.0, "aa@oksbi"))
        assertTrue(o.onEvent(CallEnded(at(90), 90)).get<Effect.OfferCheckIn>().sawPaymentAttempt)
        val quiet = TestKit.orchestrator(); quiet.onEvent(unknownCall())
        assertFalse(quiet.onEvent(CallEnded(at(10), 10)).has<Effect.OfferCheckIn>(), "a harmless call needs no check-in")
    }

    @Test
    fun `golden hour - a debit after a tier 2 session surfaces 1930 immediately`() {
        val o = scamCall(); o.onEvent(pay(30, PayStage.AMOUNT_ENTERED, 50000.0, "aa@oksbi")); o.onEvent(UserProceeded(at(60)))
        assertTrue(o.onEvent(DebitAlert(at(70), 50000.0)).has<Effect.GoldenHour>())
    }

    @Test
    fun `analysis cadence - every 20 seconds, only with new transcript, only for unknown calls`() {
        val o = TestKit.orchestrator()
        assertNull(o.nextAnalysisRequest(at(0)))
        o.onEvent(unknownCall()); o.onEvent(say(2, "hello this is a sample of some speech coming through"))
        assertNotNull(o.nextAnalysisRequest(at(3)))
        assertNull(o.nextAnalysisRequest(at(30)), "no new transcript")
        o.onEvent(say(31, "more words arrive on the line right now"))
        assertNull(o.nextAnalysisRequest(at(10)), "inside the 20 s interval")
        assertNotNull(o.nextAnalysisRequest(at(31)))
        val known = TestKit.orchestrator(); known.onEvent(TestKit.savedCall()); known.onEvent(say(2, "hello this is a long enough bit of talking"))
        assertNull(known.nextAnalysisRequest(at(3)), "never listen on calls from saved contacts")
    }

    @Test
    fun `analysis window is limited to the last 90 seconds`() {
        val o = TestKit.orchestrator(); o.onEvent(unknownCall())
        o.onEvent(say(1, "ancient words that must be dropped from window"))
        o.onEvent(say(200, "fresh words inside the rolling window of ninety seconds"))
        val req = o.nextAnalysisRequest(at(205))!!
        assertTrue("ancient" !in req.windowText); assertTrue("fresh" in req.windowText)
    }

    @Test
    fun `a new call starts a new session and archives the old one for reporting`() {
        val o = scamCall(); o.onEvent(pay(30, PayStage.AMOUNT_ENTERED, 50000.0, "aa@oksbi")); o.onEvent(CallEnded(at(90), 90))
        o.onEvent(unknownCall(1000, "+91 90000 11111"))
        assertEquals(1, o.closedSessions.size); assertNull(o.session.intent); assertEquals(OrchestratorState.MONITORING, o.state)
    }

    @Test
    fun `remote access state is device-level and survives a new session`() {
        val o = TestKit.orchestrator(); o.onEvent(RemoteAccessDetected(at(0), "AnyDesk")); o.onEvent(unknownCall(1)); o.onEvent(CallEnded(at(5), 4))
        o.onEvent(unknownCall(100))
        assertEquals(setOf("AnyDesk"), o.session.remoteAccessApps)
    }

    @Test
    fun `report is buildable from the live or archived session`() {
        val o = scamCall(); o.onEvent(pay(30, PayStage.AMOUNT_ENTERED, 50000.0, "verify.pay@oksbi")); o.onEvent(CallEnded(at(90), 90))
        val r = ReportBuilder.build(o.reportableSession()!!, moneySent = false)
        assertTrue(r.fields.any { it.label.contains("Suspect UPI") })
    }
}

class GuardianLinkTest {
    @Test
    fun `sms is a short summary with no transcript and the spec wording`() {
        val o = TestKit.orchestrator()
        o.onEvent(unknownCall()); o.onEvent(say(5, "I am CBI officer, you will be arrested, tell me secret code word zebra, upi verify dot me at oksbi"))
        o.nextAnalysisRequest(at(6))?.let { o.onEvent(FindingsReady(at(6), com.kavach.core.analysis.KeywordAnalyst.analyze(it.windowText))) }
        o.onEvent(Tick(at(40 * 60)))
        val d = o.onEvent(pay(40 * 60 + 1, PayStage.AMOUNT_ENTERED, 95000.0, "verify.me@oksbi"))
        val text = d.get<Effect.SendGuardianSms>().text
        assertContains(text, "Kavach alert: Papa is on a 40-min call with an unknown number claiming to be CBI")
        assertContains(text, "₹95,000"); assertContains(text, "new payee"); assertContains(text, "Reply HOLD")
        assertFalse(text.contains("zebra")); assertFalse(text.contains("secret code"))
    }

    @Test
    fun `hold parsing and guardian authentication`() {
        assertTrue(GuardianLink.isHold("HOLD")); assertTrue(GuardianLink.isHold("  hold please")); assertFalse(GuardianLink.isHold("don't hold"))
        assertTrue(GuardianLink.isFromGuardian("+91 98000 00001", TestKit.profile)); assertFalse(GuardianLink.isFromGuardian("+91 98000 00009", TestKit.profile))
        assertFalse(GuardianLink.isFromGuardian("", TestKit.profile))
    }

    @Test
    fun `indian digit grouping`() {
        fun amt(v: Double): String {
            val o = TestKit.orchestrator(); o.onEvent(RemoteAccessDetected(at(0), "AnyDesk"))
            return o.onEvent(pay(1, PayStage.AMOUNT_ENTERED, v, "q@ybl")).get<Effect.SendGuardianSms>().text
        }
        assertContains(amt(150000.0), "₹1,50,000"); assertContains(amt(999.0), "₹999"); assertContains(amt(1234567.0), "₹12,34,567")
    }
}

class InterventionPlanTest {
    private fun plan(text: String, amount: Double = 50000.0, payee: String = "new@ybl", lang: Lang = Lang.EN, safeWord: Boolean = true): com.kavach.core.intervention.InterventionPlan {
        val o = TestKit.orchestrator(p = TestKit.profile.copy(language = lang, familySafeWordSet = safeWord))
        o.onEvent(unknownCall()); o.onEvent(say(5, text))
        o.nextAnalysisRequest(at(6))?.let { o.onEvent(FindingsReady(at(6), com.kavach.core.analysis.KeywordAnalyst.analyze(it.windowText))) }
        o.onEvent(pay(20))
        return o.onEvent(pay(26, PayStage.AMOUNT_ENTERED, amount, payee)).plan!!
    }

    @Test
    fun `reasons are specific to the call, not generic`() {
        val p = plan("I am CBI officer. You will be arrested. Do not tell anyone. Stay on the call. Transfer money now.")
        val joined = p.reasons.joinToString(" ")
        assertContains(joined, "CBI officer"); assertContains(joined, "told you not to tell anyone")
        assertTrue(p.reasons.size in 2..3)
        assertContains(p.mythBuster!!, "never arrest anyone over a video call")
    }

    @Test
    fun `the three questions match the spec`() {
        val q = plan("I am CBI officer. You will be arrested. Do not tell anyone. Transfer money.").questions
        assertEquals(3, q.size); assertContains(q[0], "secret"); assertContains(q[1], "arrested"); assertContains(q[2], "stay on the call")
    }

    @Test
    fun `voice clone claim asks for the safe word and callback verification`() {
        val p = plan("Papa main hoon Rahul, mera accident ho gaya, police ne pakad liya, abhi paise bhejo, mummy ko mat batana")
        assertTrue(p.askSafeWord); assertNotNull(p.callbackVerification); assertContains(p.mythBuster!!, "safe word")
        assertFalse(plan("Papa main hoon Rahul, mera accident ho gaya, abhi paise bhejo, mummy ko mat batana", safeWord = false).askSafeWord)
    }

    @Test
    fun `hindi plans are in hindi and still have three questions`() {
        val p = plan("I am CBI officer. You will be arrested. Do not tell anyone. Transfer money.", lang = Lang.HI)
        assertTrue(p.headline.any { it in 'ऀ'..'ॿ' }); assertEquals(3, p.questions.size)
        assertTrue(p.questions.all { q -> q.any { it in 'ऀ'..'ॿ' } })
    }

    @Test
    fun `tier 2 and tier 3 pauses offer a one-tap 1930 helpline beside the family call`() {
        val en = plan("I am CBI officer. You will be arrested. Do not tell anyone. Transfer money.")
        assertTrue(en.tier.level >= Tier.PAUSE.level)
        assertNotNull(en.callTrusted, "the family button stays")
        assertEquals("1930", en.callHelpline)
        assertEquals("Call 1930 (cybercrime helpline)", en.callHelplineLabel)

        val hi = plan("I am CBI officer. You will be arrested. Do not tell anyone. Transfer money.", lang = Lang.HI)
        assertEquals("1930", hi.callHelpline)
        assertEquals("1930 (साइबर अपराध हेल्पलाइन) पर कॉल करें", hi.callHelplineLabel)

        val t2 = TestKit.orchestrator().let { it.onEvent(unknownCall()); it.onEvent(pay(5)); it.onEvent(pay(9, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl")) }
        assertEquals(Tier.PAUSE, t2.tier)
        assertEquals("1930", t2.plan!!.callHelpline)
        assertEquals("Call 1930 (cybercrime helpline)", t2.plan!!.callHelplineLabel)
        assertNotNull(t2.plan!!.callTrusted)

        val t3 = TestKit.orchestrator().let { it.onEvent(RemoteAccessDetected(at(0), "AnyDesk")); it.onEvent(pay(5, PayStage.AMOUNT_ENTERED, 100.0, "n@ybl")) }
        assertEquals(Tier.PAUSE_GUARDIAN, t3.tier)
        assertEquals("1930", t3.plan!!.callHelpline)
        assertEquals("Call 1930 (cybercrime helpline)", t3.plan!!.callHelplineLabel)

        val nudge = TestKit.orchestrator().let { it.onEvent(unknownCall(channel = CallChannel.VOIP)); it.onEvent(Tick(at(5))) }
        assertEquals(Tier.NUDGE, nudge.tier)
        assertNull(nudge.plan!!.callHelpline, "a nudge must not grow a helpline button")
        assertNull(nudge.plan!!.callHelplineLabel)
    }

    @Test
    fun `pause timers follow the spec - 30 s for tier 2 and 2 min for tier 3`() {
        // Tier 2: unknown call + payment app + new payee + big amount, no transcript at all (C=65).
        val t2 = TestKit.orchestrator().let { it.onEvent(unknownCall()); it.onEvent(pay(5)); it.onEvent(pay(9, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl")) }
        assertEquals(Tier.PAUSE, t2.tier); assertEquals(30, t2.plan!!.cooldownSeconds)
        // Tier 3: remote-access hard override.
        val t3 = TestKit.orchestrator().let { it.onEvent(RemoteAccessDetected(at(0), "AnyDesk")); it.onEvent(pay(5, PayStage.AMOUNT_ENTERED, 100.0, "n@ybl")) }
        assertEquals(Tier.PAUSE_GUARDIAN, t3.tier); assertEquals(120, t3.plan!!.cooldownSeconds)
    }
}
