package com.kavach.core

import com.kavach.core.TestKit.at
import com.kavach.core.TestKit.pay
import com.kavach.core.TestKit.say
import com.kavach.core.TestKit.unknownCall
import com.kavach.core.analysis.HybridMessageClassifier
import com.kavach.core.analysis.KeywordTranscriptAnalyst
import com.kavach.core.analysis.TranscriptAnalyst
import com.kavach.core.model.*
import com.kavach.core.orchestrator.*
import com.kavach.core.report.ReportBuilder
import com.kavach.core.risk.Tier
import com.kavach.core.sim.ScenarioLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class KavachRuntimeTest {
    private fun runtime(scope: CoroutineScope, analyst: TranscriptAnalyst = KeywordTranscriptAnalyst, clock: () -> Long) =
        KavachRuntime(TestKit.orchestrator(), analyst, HybridMessageClassifier(null), scope, clock)

    @Test
    fun `sensor events flow through to a tier 3 decision`() = runTest {
        var now = TestKit.T0
        val rt = runtime(backgroundScope) { now }
        val seen = mutableListOf<Decision>()
        backgroundScope.launch { rt.decisions.collect { seen += it } }
        rt.start()
        rt.emit(unknownCall()); rt.emit(say(5, "send to verify dot pay at oksbi, you will be arrested, do not tell anyone"))
        rt.emit(pay(20)); rt.emit(pay(26, PayStage.AMOUNT_ENTERED, 50000.0, "verify.pay@oksbi"))
        runCurrent() // NOT advanceUntilIdle(): it ignores backgroundScope tasks
        assertEquals(Tier.PAUSE_GUARDIAN, seen.last().tier)
        assertTrue(seen.flatMap { it.effects }.any { it is Effect.SendGuardianSms })
    }

    @Test
    fun `a hanging analyst never delays the rule-based intervention`() = runTest {
        val hang = object : TranscriptAnalyst { override suspend fun analyze(req: AnalysisRequest) = awaitCancellation() }
        val rt = runtime(backgroundScope, hang) { TestKit.T0 }
        val seen = mutableListOf<Decision>()
        backgroundScope.launch { rt.decisions.collect { seen += it } }
        rt.start()
        rt.emit(unknownCall()); rt.emit(say(5, "this should trigger an analysis request which will hang forever"))
        rt.emit(pay(20)); rt.emit(pay(26, PayStage.AMOUNT_ENTERED, 50000.0, "someone@oksbi"))
        runCurrent()
        assertEquals(Tier.PAUSE, seen.last().tier, "C=65 alone must pause while the LLM is still hanging")
    }

    @Test
    fun `message events produce otp and collect events and a verdict`() = runTest {
        val rt = runtime(backgroundScope) { TestKit.T0 }
        val seen = mutableListOf<Decision>()
        backgroundScope.launch { rt.decisions.collect { seen += it } }
        rt.start()
        rt.emit(unknownCall())
        rt.emit(MessageReceived(at(10), "VM-HDFCBK", "482913 is your OTP, do not share"))
        rt.emit(MessageReceived(at(12), "+91 98888 77777", "Your KYC will expire, account blocked. https://sbi-kyc.xyz/u"))
        runCurrent() // NOT advanceUntilIdle(): it ignores backgroundScope tasks
        val rules = seen.flatMap { it.result.rules.map { r -> r.code.name } }
        assertContains(rules, "OTP_OR_COLLECT_DURING_CALL"); assertContains(rules, "RELATED_SMS_FLAGGED")
    }

    @Test
    fun `ticker advances call duration so long-call rules fire without other events`() = runTest {
        var now = TestKit.T0
        val rt = KavachRuntime(TestKit.orchestrator(), KeywordTranscriptAnalyst, HybridMessageClassifier(null), backgroundScope, { now }, tickEveryMs = 60_000)
        val seen = mutableListOf<Decision>()
        backgroundScope.launch { rt.decisions.collect { seen += it } }
        rt.start(); rt.emit(unknownCall(0))
        runCurrent()
        now = at(11 * 60); advanceTimeBy(11 * 60_000L); runCurrent()
        assertTrue(seen.last().result.rules.any { it.code.name == "LONG_CALL" })
        assertTrue(seen.toList().isNotEmpty())
    }
}

class ReportBuilderTest {
    private fun scamSession(): com.kavach.core.state.SessionState {
        val o = TestKit.orchestrator()
        o.onEvent(unknownCall()); o.onEvent(say(5, "I am Inspector Sharma from Cyber Crime. You will be arrested. Do not tell anyone. Send to verify dot pay at oksbi"))
        o.nextAnalysisRequest(at(6))?.let { o.onEvent(FindingsReady(at(6), com.kavach.core.analysis.KeywordAnalyst.analyze(it.windowText))) }
        o.onEvent(pay(30, PayStage.AMOUNT_ENTERED, 50000.0, "verify.pay@oksbi")); o.onEvent(CallEnded(at(95), 95))
        return o.session
    }

    @Test
    fun `fields match the cybercrime portal layout and carry the suspect identifiers`() {
        val r = ReportBuilder.build(scamSession(), moneySent = false)
        val labels = r.fields.map { it.label }
        assertContains(labels, "Incident category"); assertContains(labels, "Suspect phone number(s)"); assertContains(labels, "Suspect UPI ID / account number(s)")
        assertEquals("Online financial fraud", r.fields.first { it.label == "Incident category" }.value)
        assertContains(r.fields.first { it.label == "Sub-category" }.value, "digital arrest")
        assertContains(r.suspectPayees, "verify.pay@oksbi"); assertEquals(listOf("+91 99887 76655"), r.suspectNumbers)
        assertEquals("1 min 35 s", r.contactDuration)
    }

    @Test
    fun `when money was sent the screen leads with calling 1930`() {
        val paid = ReportBuilder.build(scamSession(), moneySent = true)
        assertTrue(paid.actions.callFirst); assertTrue(ReportBuilder.renderText(paid).lines().drop(1).first().contains("CALL 1930 NOW"))
        assertTrue(paid.transaction.any { it.label == "Amount" && it.value == "₹50000" })
        val attempt = ReportBuilder.build(scamSession(), moneySent = false)
        assertFalse(attempt.actions.callFirst); assertTrue(attempt.transaction.isEmpty()); assertContains(attempt.actions.chakshuUrl, "sancharsaathi")
    }

    @Test
    fun `narrative is first person, honest about completion and contains only real excerpts`() {
        val n = ReportBuilder.build(scamSession(), moneySent = false).narrative
        assertContains(n, "I did not complete a payment"); assertContains(n, "not saved in my contacts"); assertContains(n, "captured automatically by the Kavach app")
        assertFalse(ReportBuilder.build(scamSession(), true).narrative.contains("did not complete"))
    }

    @Test
    fun `timeline is chronological and non-empty`() {
        val r = ReportBuilder.build(scamSession(), false)
        assertTrue(r.timeline.size >= 4); assertEquals(r.timeline.map { it.atMs }, r.timeline.map { it.atMs }.sorted())
    }
}

class ScenarioIndexTest {
    @Test
    fun `index lists exactly the scenario files on disk and each parses`() {
        val dir = TestKit.scenarioDir()
        val onDisk = dir.listFiles { f -> f.extension == "json" && f.name != "index.json" }!!.map { it.nameWithoutExtension }.toSet()
        val listed = ScenarioLoader.loadBundled().map { it.id }.toSet()
        assertEquals(onDisk, listed, "scenarios/index.json is out of sync with the folder")
    }

    @Test
    fun `scenario file names equal their ids`() {
        val dir = TestKit.scenarioDir()
        ScenarioLoader.loadBundled().forEach { assertTrue(java.io.File(dir, "${it.id}.json").exists(), it.id) }
    }
}
