package com.kavach.app.ui

import com.kavach.core.analysis.HybridMessageClassifier
import com.kavach.core.analysis.KeywordTranscriptAnalyst
import com.kavach.core.baseline.InMemoryBaseline
import com.kavach.core.baseline.PastPayment
import com.kavach.core.config.RiskConfig
import com.kavach.core.model.CallStarted
import com.kavach.core.model.PayStage
import com.kavach.core.model.TranscriptChunk
import com.kavach.core.orchestrator.Decision
import com.kavach.core.orchestrator.Effect
import com.kavach.core.orchestrator.KavachRuntime
import com.kavach.core.orchestrator.Orchestrator
import com.kavach.core.risk.Tier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amount and payee mapping, plus the coerced role-play fed through [Orchestrator] and [KavachRuntime].
 * That is the live engine's decision path. The scenario player is not used.
 */
class KavachPayTest {
    @Test
    fun `amount and payee map to opened then amount-entered events`() {
        assertEquals(50_000.0, KavachPay.parseAmountRupees("50,000"))
        assertEquals(50_000.0, KavachPay.parseAmountRupees("₹50,000"))
        assertEquals(1_500.5, KavachPay.parseAmountRupees("Rs. 1,500.50"))
        assertEquals(500.0, KavachPay.parseAmountRupees("500"))
        assertNull(KavachPay.parseAmountRupees(""))
        assertNull(KavachPay.parseAmountRupees("0"))
        assertNull(KavachPay.parseAmountRupees("abc"))
        assertNull(KavachPay.parseAmountRupees("-20"))

        val rahul = KavachPay.resolve(PayForm(selection = "rahul", amountText = "1,500", note = "secret-note-xyz"))
        assertEquals("rahul.son@okhdfcbank", rahul!!.upiId)
        assertTrue(rahul.saved)
        val events = KavachPay.paymentIntents(rahul, 1_000)
        assertEquals(listOf(PayStage.OPENED, PayStage.AMOUNT_ENTERED), events.map { it.stage })
        assertEquals(KavachPay.APP_NAME, events[0].app)
        assertNull(events[0].amountRupees)
        assertNull(events[0].payee)
        assertEquals(1_500.0, events[1].amountRupees)
        assertEquals("rahul.son@okhdfcbank", events[1].payee)
        assertEquals(1_001, events[1].atMs)
        assertTrue(events.none { it.toString().contains("secret-note-xyz") })
        assertTrue(events.none { it.stage == PayStage.PIN_SCREEN })

        val created = KavachPay.resolve(PayForm(selection = KavachPay.NEW_PAYEE, newUpiId = "  Stranger.Pay@oksbi ", amountText = "50000"))
        assertEquals("Stranger.Pay@oksbi", created!!.upiId)
        assertFalse(created.saved)
        assertNull(KavachPay.resolve(PayForm(selection = KavachPay.NEW_PAYEE, newUpiId = "not a upi", amountText = "10")))
        assertEquals(KavachPay.PACKAGE_NAME, "com.kavach.app.pay")
        assertEquals("Kavach Pay (demo payment app)", KavachPay.NAV_LABEL)
        assertEquals("DEMO: no real money", KavachPay.DEMO_BANNER)
        assertEquals("Payment simulated, no money moved", KavachPay.SUCCESS_TEXT)
    }

    @Test
    fun `pin length is accepted from 4 to 6 and the pin event carries no digits`() {
        assertFalse(KavachPay.isAcceptablePinLength(3))
        assertTrue(KavachPay.isAcceptablePinLength(4))
        assertTrue(KavachPay.isAcceptablePinLength(5))
        assertTrue(KavachPay.isAcceptablePinLength(6))
        assertFalse(KavachPay.isAcceptablePinLength(7))
        val pay = KavachPay.resolve(KavachPay.rolePlayForm(RolePlay.NORMAL))!!
        val pin = KavachPay.pinIntent(pay, 50)
        assertEquals(PayStage.PIN_SCREEN, pin.stage)
        assertEquals(pay.upiId, pin.payee)
        assertEquals(pay.amountRupees, pin.amountRupees)
    }

    @Test
    fun `role-play normal stays quiet and coerced pauses before any pin screen`() {
        val quiet = pay(orchestrator(empty = true), RolePlay.NORMAL, daytime())
        assertTrue(quiet.all { it.tier.level < Tier.PAUSE.level }, quiet.joinToString { it.tier.name })
        assertTrue(quiet.none { d -> d.effects.any { it is Effect.ShowPause } })

        val saved = orchestrator(
            empty = false,
            history = (0 until 4).map { PastPayment("rahul.son@okhdfcbank", 1_500.0, daytime() - 86_400_000L * (it + 1)) },
        )
        val savedPay = pay(saved, RolePlay.NORMAL, daytime())
        assertEquals(Tier.NONE, savedPay.last().tier)
        assertTrue(savedPay.none { d -> d.effects.any { it is Effect.ShowPause } })

        val o = orchestrator(empty = true)
        feed(o, KavachPay.contextEvents(RolePlay.COERCED, daytime()))
        val coerced = KavachPay.resolve(KavachPay.rolePlayForm(RolePlay.COERCED))!!
        assertEquals(KavachPay.COERCED_UPI, coerced.upiId)
        val intents = KavachPay.paymentIntents(coerced, daytime() + 5_000)
        assertTrue(intents.none { it.stage == PayStage.PIN_SCREEN })
        val decisions = intents.map { o.onEvent(it) }
        val opened = decisions.first()
        val amount = decisions.last()
        assertTrue(opened.effects.none { it is Effect.ShowPause }, "pause must wait for the amount and payee")
        assertTrue(amount.tier.level >= Tier.PAUSE.level, "tier=${amount.tier} rules=${amount.result.rules} override=${amount.result.hardOverride}")
        assertTrue(amount.effects.any { it is Effect.ShowPause })
        assertTrue(KavachPay.pinWaitsForPause(amount.tier.level))

        feed(o, KavachPay.contextEvents(RolePlay.NORMAL, daytime() + 9_000))
        val after = pay(o, RolePlay.NORMAL, daytime() + 12_000)
        assertTrue(after.all { it.tier.level < Tier.PAUSE.level }, after.joinToString { "${it.tier}/${it.result.rules}" })
        assertTrue(after.none { d -> d.effects.any { it is Effect.ShowPause } })
    }

    @Test
    fun `coerced context is an unknown call plus a transcript, and normal clears the call`() {
        val coerced = KavachPay.contextEvents(RolePlay.COERCED, 10)
        val call = coerced.filterIsInstance<CallStarted>().single()
        assertFalse(call.isInContacts)
        assertEquals(KavachPay.COERCED_CALLER, call.number)
        val line = coerced.filterIsInstance<TranscriptChunk>().single()
        assertEquals(KavachPay.COERCED_TRANSCRIPT, line.text)
        assertTrue(line.text.contains("verify dot pay at oksbi"))

        val normal = KavachPay.contextEvents(RolePlay.NORMAL, 10)
        assertTrue(normal.none { it is TranscriptChunk })
        assertTrue(normal.filterIsInstance<CallStarted>().single().isInContacts)
    }

    @Test
    fun `coerced role-play pauses through KavachRuntime before the pin screen`() = runBlocking {
        val orchestrator = orchestrator(empty = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val runtime = KavachRuntime(
            orchestrator,
            KeywordTranscriptAnalyst,
            HybridMessageClassifier(null),
            scope,
            clock = { daytime() },
            tickEveryMs = Long.MAX_VALUE / 4,
        )
        val seen = mutableListOf<Decision>()
        val lock = Any()
        val collect = scope.launch {
            runtime.decisions.collect { decision -> synchronized(lock) { seen += decision } }
        }
        runtime.start()
        try {
            delay(50)
            val start = daytime()
            KavachPay.contextEvents(RolePlay.COERCED, start).forEach { runtime.emit(it) }
            val pay = KavachPay.resolve(KavachPay.rolePlayForm(RolePlay.COERCED))!!
            val intents = KavachPay.paymentIntents(pay, start + 5_000)
            assertTrue(intents.none { it.stage == PayStage.PIN_SCREEN })
            intents.forEach { runtime.emit(it) }
            val amountAt = intents.last().atMs
            val deadline = System.nanoTime() + 3_000_000_000L
            var pause: Decision? = null
            while (System.nanoTime() < deadline && pause == null) {
                pause = synchronized(lock) {
                    seen.firstOrNull { it.atMs >= amountAt && it.effects.any { effect -> effect is Effect.ShowPause } }
                }
                if (pause == null) delay(20)
            }
            assertTrue(pause != null, "no pause in ${synchronized(lock) { seen.map { it.tier.name to it.atMs } }}")
            assertTrue(pause!!.tier.level >= Tier.PAUSE.level)
        } finally {
            runtime.stop()
            collect.cancel()
            scope.cancel()
        }
    }

    private fun daytime(): Long =
        LocalDateTime.parse("2026-11-03T15:00:00").atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    private fun orchestrator(empty: Boolean, history: List<PastPayment> = emptyList()): Orchestrator {
        val baseline = if (empty) InMemoryBaseline() else InMemoryBaseline(history)
        return Orchestrator(RiskConfig.loadDefault(), baseline)
    }

    private fun feed(orchestrator: Orchestrator, events: List<com.kavach.core.model.KavachEvent>) {
        events.forEach { orchestrator.onEvent(it) }
    }

    private fun pay(orchestrator: Orchestrator, role: RolePlay, atMs: Long): List<Decision> {
        feed(orchestrator, KavachPay.contextEvents(role, atMs))
        val resolved = KavachPay.resolve(KavachPay.rolePlayForm(role))!!
        return KavachPay.paymentIntents(resolved, atMs + 5_000).map { orchestrator.onEvent(it) }
    }
}
