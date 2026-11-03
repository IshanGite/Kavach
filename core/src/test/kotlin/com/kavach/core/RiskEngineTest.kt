package com.kavach.core

import com.kavach.core.TestKit.at
import com.kavach.core.TestKit.pay
import com.kavach.core.TestKit.say
import com.kavach.core.TestKit.session
import com.kavach.core.TestKit.unknownCall
import com.kavach.core.baseline.PastPayment
import com.kavach.core.config.RiskConfig
import com.kavach.core.model.*
import com.kavach.core.risk.*
import com.kavach.core.state.reduce
import kotlin.test.*

class RiskEngineTest {
    private val engine = TestKit.engine()

    private fun points(r: RiskResult) = r.rules.associate { it.code to it.points }

    @Test
    fun `spec table - config weights match the documented risk fusion table`() {
        val p = RiskConfig().points
        assertEquals(20, p.unknownLiveCall); assertEquals(10, p.callOver10Min); assertEquals(15, p.callOver30Min)
        assertEquals(15, p.unknownVoip); assertEquals(25, p.paymentAppDuringCall); assertEquals(10, p.newPayee)
        assertEquals(10, p.amountOver3xMedian); assertEquals(5, p.oddHour); assertEquals(25, p.remoteAccessActive)
        assertEquals(10, p.otpOrCollectDuringCall); assertEquals(10, p.syntheticVoice); assertEquals(40, p.numberOnScamList)
        assertEquals(10, p.relatedSmsFlagged)
        val t = RiskConfig().tiers
        assertEquals(listOf(35, 60, 80), listOf(t.nudge, t.pause, t.pauseGuardian))
        assertEquals(0.6, RiskConfig().contextWeight); assertEquals(0.4, RiskConfig().languageWeight)
    }

    @Test
    fun `bundled risk_config json equals the compiled defaults, so docs and code cannot drift`() {
        assertEquals(RiskConfig(), RiskConfig.loadDefault())
    }

    @Test
    fun `strict config parsing rejects a typo instead of silently using a default`() {
        assertFailsWith<Exception> { RiskConfig.fromJson("""{"points":{"unknwon_live_call":99}}""") }
    }

    @Test
    fun `idle device scores zero`() {
        val r = engine.evaluate(session())
        assertEquals(0, r.risk); assertEquals(Tier.NONE, r.tier)
    }

    @Test
    fun `unknown live call alone is 20 and stays silent`() {
        val r = engine.evaluate(session(unknownCall(), nowSec = 5))
        assertEquals(20, r.contextScore); assertEquals(Tier.NONE, r.tier)
    }

    @Test
    fun `call from a saved contact triggers none of the call-based rules even with a payment app open`() {
        val r = engine.evaluate(session(TestKit.savedCall(), pay(10, PayStage.AMOUNT_ENTERED, 1500.0, "rahul.son@okhdfcbank")))
        val callRules = setOf(RuleCode.UNKNOWN_LIVE_CALL, RuleCode.PAYMENT_APP_DURING_CALL, RuleCode.LONG_CALL, RuleCode.UNKNOWN_VOIP_OR_VIDEO)
        assertTrue(r.rules.none { it.code in callRules }, "${r.rules}")
        assertEquals(Tier.NONE, r.tier)
    }

    @Test
    fun `long call thresholds - 10 minutes then 30 minutes`() {
        fun longCall(minutes: Int) = engine.evaluate(session(unknownCall(), nowSec = minutes * 60)).rules.firstOrNull { it.code == RuleCode.LONG_CALL }?.points
        assertNull(longCall(9)); assertEquals(10, longCall(11)); assertEquals(15, longCall(31))
    }

    @Test
    fun `voip adds 15 only for an unknown caller`() {
        assertEquals(15, points(engine.evaluate(session(unknownCall(channel = CallChannel.VOIP), nowSec = 5)))[RuleCode.UNKNOWN_VOIP_OR_VIDEO])
        val saved = session(CallStarted(at(0), "+91 98200 11122", channel = CallChannel.VOIP, isInContacts = true), nowSec = 5)
        assertNull(points(engine.evaluate(saved))[RuleCode.UNKNOWN_VOIP_OR_VIDEO])
    }

    @Test
    fun `payment app during unknown call adds 25 and is the critical moment`() {
        val r = engine.evaluate(session(unknownCall(), pay(30)))
        assertEquals(25, points(r)[RuleCode.PAYMENT_APP_DURING_CALL]); assertEquals(45, r.contextScore); assertEquals(Tier.NUDGE, r.tier)
    }

    @Test
    fun `new payee and amount above three times median`() {
        val r = engine.evaluate(session(unknownCall(), pay(30, PayStage.AMOUNT_ENTERED, 50000.0, "brand.new@oksbi")))
        assertEquals(10, points(r)[RuleCode.NEW_PAYEE]); assertEquals(10, points(r)[RuleCode.AMOUNT_ABOVE_USUAL])
        assertEquals(65, r.contextScore); assertEquals(Tier.PAUSE, r.tier)
    }

    @Test
    fun `amount rule needs enough history to mean anything`() {
        val tiny = RiskEngine(TestKit.cfg, com.kavach.core.baseline.InMemoryBaseline(listOf(PastPayment("a@ybl", 100.0, 0))))
        val r = tiny.evaluate(session(pay(0, PayStage.AMOUNT_ENTERED, 99999.0, "b@ybl")))
        assertNull(points(r)[RuleCode.AMOUNT_ABOVE_USUAL])
    }

    @Test
    fun `odd hour applies between 11pm and 6am only`() {
        val night = TestKit.T0 + 11L * 3600_000 // 02:00 next day
        val s = SessionStateAt(night)
        assertEquals(5, points(engine.evaluate(s))[RuleCode.ODD_HOUR])
        assertNull(points(engine.evaluate(SessionStateAt(TestKit.T0)))[RuleCode.ODD_HOUR])
    }

    private fun SessionStateAt(ms: Long) = com.kavach.core.state.SessionState().reduce(CallStarted(ms, "+91 99887 76655"), TestKit.cfg)

    @Test
    fun `remote access app adds 25 even without a call`() {
        val r = engine.evaluate(session(RemoteAccessDetected(at(0), "AnyDesk")))
        assertEquals(25, points(r)[RuleCode.REMOTE_ACCESS_ACTIVE])
    }

    @Test
    fun `otp or collect request during an unknown call adds 10`() {
        val r = engine.evaluate(session(unknownCall(), OtpReceived(at(20), "VM-HDFCBK")))
        assertEquals(10, points(r)[RuleCode.OTP_OR_COLLECT_DURING_CALL])
        val noCall = engine.evaluate(session(OtpReceived(at(20), "VM-HDFCBK")))
        assertNull(points(noCall)[RuleCode.OTP_OR_COLLECT_DURING_CALL])
    }

    @Test
    fun `synthetic voice needs a sustained score above 0_7, not one fluke window`() {
        val fluke = engine.evaluate(session(unknownCall(), VoiceSpoofScore(at(5), 0.2), VoiceSpoofScore(at(9), 0.2), VoiceSpoofScore(at(13), 0.95)))
        assertNull(points(fluke)[RuleCode.SYNTHETIC_VOICE])
        val sustained = engine.evaluate(session(unknownCall(), VoiceSpoofScore(at(5), 0.8), VoiceSpoofScore(at(9), 0.75), VoiceSpoofScore(at(13), 0.9)))
        assertEquals(10, points(sustained)[RuleCode.SYNTHETIC_VOICE])
    }

    @Test
    fun `scam list number adds 40`() {
        val e = RiskEngine(TestKit.cfg, TestKit.baseline(), { it.filter(Char::isDigit).takeLast(10) == "9000000001" })
        val r = e.evaluate(session(unknownCall(number = "+91 90000 00001")))
        assertEquals(60, r.contextScore); assertEquals(40, points(r)[RuleCode.NUMBER_ON_SCAM_LIST])
    }

    @Test
    fun `flagged sms counts for 24 hours then expires`() {
        val flagged = MessageVerdict(at(0), true, ScamType.DIGITAL_ARREST, 0.9, "x")
        val soon = engine.evaluate(session(flagged, unknownCall(60), nowSec = 70))
        assertEquals(10, points(soon)[RuleCode.RELATED_SMS_FLAGGED])
        val old = engine.evaluate(session(flagged, unknownCall(25 * 3600), nowSec = 25 * 3600 + 5))
        assertNull(points(old)[RuleCode.RELATED_SMS_FLAGGED])
    }

    @Test
    fun `context score is capped at 100`() {
        val e = RiskEngine(TestKit.cfg, TestKit.baseline(), { true })
        val r = e.evaluate(session(unknownCall(channel = CallChannel.VOIP), RemoteAccessDetected(at(1), "AnyDesk"), OtpReceived(at(2), "x"),
            pay(3, PayStage.AMOUNT_ENTERED, 99999.0, "z@ybl"), nowSec = 31 * 60))
        assertEquals(100, r.contextScore)
    }

    @Test
    fun `fusion uses 0_6 C plus 0_4 L only once an analysis exists`() {
        val base = session(unknownCall(), pay(5, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl"), say(6, "this is a long enough sentence to count as transcript"))
        assertNull(engine.evaluate(base).languageScore, "no analysis yet: R must equal C")
        assertEquals(engine.evaluate(base).contextScore, engine.evaluate(base).risk)
        val withL = base.reduce(FindingsReady(at(7), TacticsFindings(llmRisk = 100)), TestKit.cfg)
        val r = engine.evaluate(withL)
        assertEquals(100, r.languageScore)
        assertEquals((0.6 * r.contextScore + 40).toInt(), r.risk)
    }

    @Test
    fun `language score attacks fast and releases slowly`() {
        fun l(vararg risks: Int): Int? {
            var s = session(unknownCall(), say(1, "this is a long enough sentence to count as transcript"))
            risks.forEach { s = s.reduce(FindingsReady(at(10), TacticsFindings(llmRisk = it)), TestKit.cfg) }
            return engine.evaluate(s).languageScore
        }
        assertEquals(88, l(13, 33, 88), "a spike must register immediately, not be averaged down")
        assertEquals(63, l(90, 90, 10), "a later dip is damped by the recent mean")
    }

    @Test
    fun `tiers follow thresholds 35 60 80 when a payment intent exists`() {
        // With no transcript R = C, so the context points alone walk the tiers.
        val call = unknownCall()
        val c45 = engine.evaluate(session(call, pay(5)))                                              // 20 + 25
        val c65 = engine.evaluate(session(call, pay(5, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl")))   // + new payee + big amount
        assertEquals(45, c45.risk); assertEquals(Tier.NUDGE, c45.tier)
        assertEquals(65, c65.risk); assertEquals(Tier.PAUSE, c65.tier)
        // 02:00 (odd hour +5) and an OTP during the call (+10) lift 65 to 80 exactly.
        val night = TestKit.T0 + 11L * 3600_000
        val c80 = engine.evaluate(session(
            CallStarted(night, "+91 99887 76655"), OtpReceived(night + 1000, "x"),
            PaymentIntent(night + 5000, "PhonePe", PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl"),
        ))
        assertEquals(80, c80.risk); assertEquals(Tier.PAUSE_GUARDIAN, c80.tier)
    }

    @Test
    fun `a benign transcript pulls a mid-strength context down (language is real evidence, not decoration)`() {
        val s = session(unknownCall(), pay(5, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl"), say(6, "the plumber will come tomorrow at five is that okay"))
            .reduce(FindingsReady(at(7), TacticsFindings(llmRisk = 0)), TestKit.cfg)
        val r = engine.evaluate(s)
        assertEquals(65, r.contextScore); assertEquals(39, r.risk); assertEquals(Tier.NUDGE, r.tier)
    }

    // ------------------------------------------------------------ guards

    @Test
    fun `guard - without a payment intent the maximum is a nudge`() {
        val e = RiskEngine(TestKit.cfg, TestKit.baseline(), { true })
        val r = e.evaluate(session(unknownCall(number = "+91 90000 00001"), RemoteAccessDetected(at(1), "AnyDesk"), nowSec = 31 * 60))
        assertTrue(r.contextScore >= 80)
        assertEquals(Tier.NUDGE, r.tier); assertTrue(Guard.NO_PAYMENT_INTENT_CAPS_AT_NUDGE in r.guards)
    }

    @Test
    fun `guard - saved frequent payee with no live call is capped at zero even when the transcript from an earlier call was alarming`() {
        // The call has ENDED, the earlier analysis was at 100, and a flagged SMS adds points: R lands in nudge territory.
        val s = session(
            unknownCall(), say(2, "you will be arrested do not tell anyone transfer money now to me"),
            MessageVerdict(at(3), true, ScamType.KYC_BANK, 0.95, "x"),
        ).reduce(FindingsReady(at(4), TacticsFindings(llmRisk = 100)), TestKit.cfg)
            .reduce(CallEnded(at(60), 60), TestKit.cfg)
            .reduce(PaymentIntent(at(70), "PhonePe", PayStage.AMOUNT_ENTERED, 30000.0, "rahul.son@okhdfcbank"), TestKit.cfg)
        val r = engine.evaluate(s)
        assertTrue(r.risk >= 35, "setup must reach nudge territory before the guard, got ${r.risk}")
        assertEquals(Tier.NONE, r.tier); assertTrue(Guard.SAVED_PAYEE_NO_CALL_CAPS_AT_ZERO in r.guards)
    }

    @Test
    fun `guard does not apply while an unknown call is live`() {
        val r = engine.evaluate(session(unknownCall(), pay(10, PayStage.AMOUNT_ENTERED, 30000.0, "rahul.son@okhdfcbank")))
        assertFalse(Guard.SAVED_PAYEE_NO_CALL_CAPS_AT_ZERO in r.guards)
    }

    @Test
    fun `a payee paid fewer than three times is not saved-frequent`() {
        val e = RiskEngine(TestKit.cfg, TestKit.baseline(listOf(PastPayment("twice@ybl", 100.0, 0), PastPayment("twice@ybl", 100.0, 0))))
        assertFalse(Guard.SAVED_PAYEE_NO_CALL_CAPS_AT_ZERO in e.evaluate(session(pay(1, PayStage.AMOUNT_ENTERED, 100.0, "twice@ybl"))).guards)
    }

    // ------------------------------------------------------------ hard overrides

    @Test
    fun `hard override - remote access plus payment intent goes straight to tier 3`() {
        val r = engine.evaluate(session(RemoteAccessDetected(at(0), "AnyDesk"), pay(10, PayStage.OPENED, app = "HDFC Bank")))
        assertEquals(Tier.PAUSE_GUARDIAN, r.tier); assertEquals(HardOverride.REMOTE_ACCESS_WITH_PAYMENT, r.hardOverride)
    }

    @Test
    fun `remote access override beats the saved-payee guard`() {
        val r = engine.evaluate(session(RemoteAccessDetected(at(0), "AnyDesk"), pay(10, PayStage.AMOUNT_ENTERED, 200.0, "ramesh.kirana@ybl")))
        assertEquals(Tier.PAUSE_GUARDIAN, r.tier)
    }

    @Test
    fun `hard override - payee spoken on the call and then typed on the pay screen goes to tier 3`() {
        val r = engine.evaluate(session(unknownCall(), say(5, "send to verify dot pay at oksbi right now"), pay(30, PayStage.AMOUNT_ENTERED, 100.0, "verify.pay@oksbi")))
        assertEquals(HardOverride.PAYEE_FROM_CALL, r.hardOverride); assertEquals(Tier.PAUSE_GUARDIAN, r.tier)
    }

    @Test
    fun `an unrelated payee does not trigger the payee-from-call override`() {
        val r = engine.evaluate(session(unknownCall(), say(5, "send to verify dot pay at oksbi"), pay(30, PayStage.AMOUNT_ENTERED, 100.0, "someone.else@ybl")))
        assertNull(r.hardOverride)
    }

    @Test
    fun `intent expires after the ttl so a stale payment screen cannot keep a pause alive`() {
        val s = session(unknownCall(), pay(10, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl"))
        val stale = s.copy(nowMs = at(10 + TestKit.cfg.paymentIntentTtlSec + 5))
        assertFalse(engine.evaluate(stale).paymentIntent)
    }

    @Test
    fun `engine is deterministic`() {
        val s = session(unknownCall(), say(5, "arrest warrant cyber crime"), pay(10, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl"))
        assertEquals(engine.evaluate(s), engine.evaluate(s))
    }
}
