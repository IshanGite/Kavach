package com.kavach.core

import com.kavach.core.TestKit.at
import com.kavach.core.analysis.KeywordAnalyst
import com.kavach.core.model.*
import com.kavach.core.orchestrator.Effect
import com.kavach.core.risk.HardOverride
import com.kavach.core.risk.Tier
import com.kavach.core.state.IntentKind
import com.kavach.core.state.reduce
import kotlin.random.Random
import kotlin.test.*

/**
 * Safety invariants that must hold for ANY event stream, not just the scripted scenarios.
 * Seeded, so a failure prints a reproducible seed.
 */
class InvariantFuzzTest {
    private val payees = listOf("ramesh.kirana@ybl", "rahul.son@okhdfcbank", "milk.dairy@upi", "new.person@oksbi", "verify.pay@oksbi", null, "Unknown Shop")
    private val phrases = listOf(
        "hello how are you", "you will be arrested", "do not tell anyone", "stay on the call", "transfer money now", "send to verify dot pay at oksbi",
        "i am inspector sharma cyber crime", "apna otp batao", "papa accident hospital need money", "the plumber comes tomorrow", "never share your otp", "abhi turant",
    )

    private fun randomEvent(r: Random, sec: Int): KavachEvent = when (r.nextInt(16)) {
        0 -> CallStarted(at(sec), "+91 9${r.nextInt(100000000, 999999999)}", channel = if (r.nextBoolean()) CallChannel.VOIP else CallChannel.CELLULAR, isInContacts = r.nextInt(4) == 0)
        1 -> CallEnded(at(sec), r.nextLong(1, 4000))
        2, 3 -> TranscriptChunk(at(sec), phrases[r.nextInt(phrases.size)] + " " + phrases[r.nextInt(phrases.size)])
        4 -> VoiceSpoofScore(at(sec), r.nextDouble())
        5 -> MessageVerdict(at(sec), r.nextBoolean(), ScamType.entries[r.nextInt(ScamType.entries.size)], r.nextDouble(), "fuzz")
        6 -> OtpReceived(at(sec), "VM-X")
        7 -> CollectRequest(at(sec), r.nextInt(10, 100000).toDouble(), payees[r.nextInt(payees.size)])
        8, 9, 10 -> PaymentIntent(at(sec), "PhonePe", PayStage.entries[r.nextInt(3)], if (r.nextBoolean()) r.nextInt(10, 200000).toDouble() else null, payees[r.nextInt(payees.size)])
        11 -> PaymentDismissed(at(sec))
        12 -> if (r.nextBoolean()) RemoteAccessDetected(at(sec), "AnyDesk") else RemoteAccessEnded(at(sec), "AnyDesk")
        13 -> FindingsReady(at(sec), KeywordAnalyst.analyze(phrases[r.nextInt(phrases.size)] + " " + phrases[r.nextInt(phrases.size)]))
        14 -> UserProceeded(at(sec))
        else -> Tick(at(sec))
    }

    @Test
    fun `random streams never crash and always respect the safety invariants`() {
        repeat(300) { seed ->
            val r = Random(seed)
            val o = TestKit.orchestrator(scam = setOf("9000000001"))
            var sec = 0
            repeat(120) {
                sec += r.nextInt(0, 90)
                val e = randomEvent(r, sec)
                val d = o.onEvent(e)
                val ctx = "seed=$seed step=$it event=$e"
                val res = d.result

                assertTrue(res.risk in 0..100 && res.contextScore in 0..100, "score out of range. $ctx")
                assertTrue(res.languageScore == null || res.languageScore in 0..100, ctx)

                // 1. A pause only ever exists around a payment intent.
                if (d.tier.level >= Tier.PAUSE.level) assertTrue(res.paymentIntent || o.pauseUntil() != null, "pause without payment intent. $ctx")
                if (res.tier.level >= Tier.PAUSE.level) assertTrue(res.paymentIntent, "engine produced tier>=2 without payment intent. $ctx")

                // 2. Saved frequent payee + no live call + no remote access + no spoken-payee match => never above tier 0.
                val intent = o.session.intent
                if (res.paymentIntent && intent?.payee in listOf("ramesh.kirana@ybl", "rahul.son@okhdfcbank", "milk.dairy@upi") &&
                    !o.session.callLive && o.session.remoteAccessApps.isEmpty() && res.hardOverride != HardOverride.PAYEE_FROM_CALL
                ) assertEquals(Tier.NONE, res.tier, "saved payee interrupted. $ctx")

                // 3. Hard overrides really are tier 3.
                if (res.hardOverride != null) assertEquals(Tier.PAUSE_GUARDIAN, res.tier, ctx)

                // 4. Nothing is shown for a pure, quiet no-call, no-remote-access, no-flag session.
                if (!o.session.callLive && o.session.remoteAccessApps.isEmpty() && o.session.smsFlaggedAtMs == null && o.session.call == null && o.session.intent == null)
                    assertEquals(Tier.NONE, d.tier, "phantom intervention. $ctx")

                // 5. Guardian SMS only at tier 3 and never to anyone but registered guardians.
                d.effects.filterIsInstance<Effect.SendGuardianSms>().forEach {
                    assertEquals(Tier.PAUSE_GUARDIAN, d.tier, ctx); assertEquals(listOf(TestKit.guardian.number), it.numbers, ctx)
                    assertFalse(it.text.contains("arrested"), "guardian SMS must not carry transcript content. $ctx")
                }
                // 6. A collect request is a payment intent and so is covered by the same gates.
                if (o.session.intentKind == IntentKind.COLLECT_REQUEST && res.paymentIntent) assertNotNull(o.session.intent, ctx)
            }
        }
    }

    @Test
    fun `replaying the same events yields identical decisions`() {
        val r = Random(42)
        val events = (0 until 200).map { randomEvent(r, it * 7) }
        fun replay(): List<Pair<Int, Int>> {
            val o = TestKit.orchestrator()
            return events.map { val d = o.onEvent(it); d.tier.level to d.result.risk }
        }
        assertEquals(replay(), replay())
    }

    @Test
    fun `tier never decreases when more suspicion is added to the same moment (monotonic in points)`() {
        val base = com.kavach.core.state.SessionState()
        val cfg = TestKit.cfg
        val engine = TestKit.engine()
        var s = base
        val adds: List<KavachEvent> = listOf(
            TestKit.unknownCall(), TestKit.pay(1, PayStage.OPENED), TestKit.pay(2, PayStage.AMOUNT_ENTERED, 50000.0, "n@ybl"),
            OtpReceived(at(3), "x"), VoiceSpoofScore(at(4), 0.9), VoiceSpoofScore(at(5), 0.9), VoiceSpoofScore(at(6), 0.9), RemoteAccessDetected(at(7), "AnyDesk"),
        )
        var last = Tier.NONE
        adds.forEach { s = s.reduce(it, cfg); val t = engine.evaluate(s).tier; assertTrue(t.level >= last.level, "tier dropped after $it"); last = t }
        assertEquals(Tier.PAUSE_GUARDIAN, last)
    }
}
