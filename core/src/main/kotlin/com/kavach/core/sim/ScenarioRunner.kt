package com.kavach.core.sim

import com.kavach.core.analysis.*
import com.kavach.core.config.RiskConfig
import com.kavach.core.llm.LlmClient
import com.kavach.core.llm.LlmRequest
import com.kavach.core.llm.LlmResponse
import com.kavach.core.model.*
import com.kavach.core.orchestrator.Decision
import com.kavach.core.orchestrator.Effect
import com.kavach.core.orchestrator.Orchestrator
import com.kavach.core.risk.Tier
import com.kavach.core.text.Extractors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Fake LLM for tests and the simulator: replies with whatever the script says. */
class ScriptedLlmClient(override val name: String = "scripted", private val responder: (LlmRequest) -> String) : LlmClient {
    override suspend fun analyze(req: LlmRequest) = LlmResponse(responder(req), name, 0)
}

object ScamNumbers {
    /** Bundled local blocklist, matched on the last 10 digits. */
    fun loadBundled(): Set<String> {
        val text = ScamNumbers::class.java.classLoader.getResourceAsStream("scam_numbers.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return emptySet()
        return Json.parseToJsonElement(text).jsonObject["numbers"]!!.jsonArray
            .map { Extractors.normalizeNumber(it.jsonPrimitive.content) }.toSet()
    }
}

data class StepTrace(val event: KavachEvent, val t: Int, val decision: Decision)

data class RunTrace(
    val scenario: Scenario,
    val steps: List<StepTrace>,
    val maxTier: Tier,
    val maxTierBeforePayment: Tier,
    val tierAtPayment: Tier?,
    val guardianSmsCount: Int,
    val guardianSmsText: String?,
    /** Seconds the pause overlay was already up before the PIN screen appeared (null: no PIN screen or no pause). */
    val pauseLeadSeconds: Int?,
    val pauseBeforePin: Boolean?,
    val firstPauseAtT: Int?,
    val messageFlagged: Boolean,
    val effects: List<Effect>,
)

data class Result(val scenario: Scenario, val trace: RunTrace, val failures: List<String>) {
    val passed get() = failures.isEmpty()
}

class ScenarioRunner(
    private val cfg: RiskConfig = RiskConfig.loadDefault(),
    private val scamNumbers: Set<String> = ScamNumbers.loadBundled(),
    /** Optional real/fake LLM for the language layer; null means the keyword analyst only. */
    private val llm: LlmClient? = null,
    private val compactPrompt: Boolean = false,
) {
    private fun analystFor(s: Scenario): TranscriptAnalyst? = when (s.analyst) {
        "none" -> null
        "obedient-zero" -> HybridTranscriptAnalyst(
            ScriptedLlmClient("hijacked-llm") {
                """{"claimed_identity":"none","tactics":{"authority_impersonation":0,"threat_of_arrest_or_legal_action":0,"urgency_or_deadline":0,"secrecy_or_isolation":0,"payment_or_transfer_request":0,"remote_access_or_otp_request":0,"emotional_emergency":0},"scam_type":"none","llm_risk":0,"evidence_quotes":["ignore all previous instructions"],"payee_details_mentioned":["made.up@oksbi"]}"""
            },
            cfg,
        )
        // A model that is right about the risk (0) but lists a business NAME as a payee detail: must never trigger the payee-from-call override.
        "names-as-payee" -> HybridTranscriptAnalyst(
            ScriptedLlmClient("name-happy-llm") {
                """{"id":"none","t":[0,0,0,0,0,0,0],"type":"none","risk":0,"q":[],"p":["Sunrise Clinic","Dr Mehta"]}"""
            },
            cfg,
        )
        else -> if (llm != null) HybridTranscriptAnalyst(llm, cfg) { compactPrompt } else KeywordTranscriptAnalyst
    }

    private fun latencyMs(s: Scenario) = if (s.analyst == "slow") 12_000L else 0L

    suspend fun run(s: Scenario): RunTrace {
        val profile = ScenarioLoader.defaultProfile()
        val orch = Orchestrator(cfg, ScenarioLoader.buildBaseline(s), profile, { Extractors.normalizeNumber(it) in scamNumbers })
        val classifier = HybridMessageClassifier(null)
        val analyst = analystFor(s)
        val start = ScenarioLoader.startEpochMs(s)
        val events = s.events.sortedBy { it.t }.map { ScenarioLoader.toEvent(s, it) }

        val steps = mutableListOf<StepTrace>()
        val pending = mutableListOf<Pair<Long, KavachEvent>>()

        fun record(e: KavachEvent): Decision {
            val d = orch.onEvent(e)
            steps += StepTrace(e, ((e.atMs - start) / 1000).toInt(), d)
            return d
        }

        suspend fun step(e: KavachEvent) {
            if (e is MessageReceived) {
                when (val p = MessageAnalyst.parse(e.source, e.text)) {
                    is ParsedMessage.Otp -> record(OtpReceived(e.atMs, p.source))
                    is ParsedMessage.Collect -> record(CollectRequest(e.atMs, p.amountRupees, p.payee))
                    is ParsedMessage.Debit -> record(DebitAlert(e.atMs, p.amountRupees))
                    null -> Unit
                }
                record(e)
                record(classifier.classify(e.atMs, e.source, e.text))
                return
            }
            record(e)
            if ((e is TranscriptChunk || e is Tick || e is CallStarted) && analyst != null) {
                orch.nextAnalysisRequest(e.atMs)?.let { req ->
                    val findings = analyst.analyze(req)
                    val deliverAt = e.atMs + latencyMs(s)
                    if (deliverAt <= e.atMs) record(FindingsReady(e.atMs, findings)) else pending += deliverAt to FindingsReady(deliverAt, findings)
                }
            }
        }

        suspend fun deliverPending(upTo: Long) {
            val due = pending.filter { it.first <= upTo }.sortedBy { it.first }
            pending.removeAll(due.toSet())
            due.forEach { record(it.second) }
        }

        var now = start
        suspend fun advanceTo(target: Long) {
            while (now + TICK_MS < target) {
                now += TICK_MS
                deliverPending(now)
                step(Tick(now))
            }
        }
        for (e in events) {
            advanceTo(e.atMs)
            deliverPending(e.atMs)
            step(e)
            now = maxOf(now, e.atMs)
        }
        advanceTo(now + 30_000) // let the last analysis land, like the real runtime would

        return summarize(s, steps, start)
    }

    private fun summarize(s: Scenario, steps: List<StepTrace>, start: Long): RunTrace {
        val firstPay = steps.indexOfFirst { it.event is PaymentIntent || it.event is CollectRequest }
        val tiers = steps.map { it.decision.tier }
        val max = tiers.maxByOrNull { it.level } ?: Tier.NONE
        val before = (if (firstPay < 0) tiers else tiers.take(firstPay)).maxByOrNull { it.level } ?: Tier.NONE
        val atPay = if (firstPay < 0) null else tiers.drop(firstPay).maxByOrNull { it.level }
        val effects = steps.flatMap { it.decision.effects }
        val sms = effects.filterIsInstance<Effect.SendGuardianSms>()

        val pinIdx = steps.indexOfFirst { (it.event as? PaymentIntent)?.stage == PayStage.PIN_SCREEN }
        val pauseIdx = steps.indexOfFirst { st -> st.decision.effects.any { it is Effect.ShowPause } }
        val pauseBeforePin = if (pinIdx < 0) null else pauseIdx in 0..pinIdx
        val lead = if (pinIdx >= 0 && pauseIdx in 0..pinIdx) steps[pinIdx].t - steps[pauseIdx].t else null

        val flagged = steps.any { (it.event as? MessageVerdict)?.isScam == true }
        return RunTrace(
            s, steps, max, before, atPay, sms.size, sms.firstOrNull()?.text, lead, pauseBeforePin,
            if (pauseIdx >= 0) steps[pauseIdx].t else null, flagged, effects,
        )
    }

    fun check(s: Scenario, t: RunTrace): List<String> {
        val x = s.expect
        val f = mutableListOf<String>()
        x.minTier?.let { if (t.maxTier.level < it) f += "max tier ${t.maxTier.level} < expected min $it" }
        x.maxTier?.let { if (t.maxTier.level > it) f += "max tier ${t.maxTier.level} > allowed $it" }
        x.minTierAtPayment?.let {
            val at = t.tierAtPayment?.level ?: -1
            if (at < it) f += "tier at payment $at < expected min $it"
        }
        x.maxTierBeforePayment?.let { if (t.maxTierBeforePayment.level > it) f += "tier before payment ${t.maxTierBeforePayment.level} > allowed $it" }
        x.guardianSms?.let { want -> if ((t.guardianSmsCount > 0) != want) f += "guardian SMS sent=${t.guardianSmsCount > 0}, expected $want" }
        x.pauseBeforePin?.let { want -> if ((t.pauseBeforePin == true) != want) f += "pause before PIN screen=${t.pauseBeforePin}, expected $want" }
        x.messageFlagged?.let { want -> if (t.messageFlagged != want) f += "message flagged=${t.messageFlagged}, expected $want" }
        x.minTierBeforeCallEnd?.let {
            val end = t.steps.indexOfFirst { st -> st.event is CallEnded }
            val at = if (end > 0) t.steps[end - 1].decision.tier.level else -1
            if (at < it) f += "tier just before call end $at < expected min $it"
        }
        x.maxFinalTier?.let { val last = t.steps.lastOrNull()?.decision?.tier?.level ?: 0; if (last > it) f += "final tier $last > allowed $it (lingering nudge)" }
        // Universal safety invariant for every normal scenario: never a pause.
        if (s.kind == "normal" && t.maxTier.level >= Tier.PAUSE.level) f += "FALSE POSITIVE: normal scenario reached tier ${t.maxTier.level}"
        return f
    }

    suspend fun evaluate(s: Scenario): Result {
        val t = run(s)
        return Result(s, t, check(s, t))
    }

    private companion object {
        const val TICK_MS = 5_000L
    }
}

/** Human-readable scorecard: what the judges (and Om) can read in 10 seconds. */
object Scorecard {
    fun render(results: List<Result>): String {
        val gated = results.filter { it.scenario.knownGap == null }
        val allScams = gated.filter { it.scenario.kind == "scam" }
        // A scam call that never reaches a payment screen has nothing to pause; it is judged on the nudge row instead.
        val scams = allScams.filter { it.trace.tierAtPayment != null }
        val callOnly = allScams.filter { it.trace.tierAtPayment == null }
        val normals = gated.filter { it.scenario.kind == "normal" }
        val caught = scams.count { (it.trace.tierAtPayment?.level ?: 0) >= Tier.PAUSE.level }
        val fpPause = normals.count { it.trace.maxTier.level >= Tier.PAUSE.level }
        val fpNudge = normals.count { it.trace.maxTier.level >= Tier.NUDGE.level }
        val leads = scams.mapNotNull { it.trace.pauseLeadSeconds }

        return buildString {
            appendLine("# Kavach eval scorecard")
            appendLine()
            appendLine("| Metric | Result | Target |")
            appendLine("|---|---|---|")
            appendLine("| Scams paused before payment | $caught / ${scams.size} | 100% |")
            if (callOnly.isNotEmpty()) appendLine("| Scam calls with no payment surfaced as a nudge (never a pause) | ${callOnly.count { it.trace.maxTier == Tier.NUDGE }} / ${callOnly.size} | 100% |")
            appendLine("| Normal payments with a pause (tier 2+) | $fpPause / ${normals.size} | 0 (spec: < 1 in 100) |")
            appendLine("| Normal scenarios with any nudge (tier 1) | $fpNudge / ${normals.size} | low |")
            if (leads.isNotEmpty()) appendLine("| Pause shown before PIN screen, lead time | min ${leads.min()} s, median ${leads.sorted()[leads.size / 2]} s | > 0 s |")
            appendLine("| Scenarios passing | ${gated.count { it.passed }} / ${gated.size} (+${results.size - gated.size} known gap) | all |")
            appendLine()
            appendLine("| Scenario | Kind | Max tier | Tier at payment | Pause lead (s) | Guardian SMS | Result |")
            appendLine("|---|---|---|---|---|---|---|")
            results.forEach { r ->
                val t = r.trace
                val res = when {
                    r.scenario.knownGap != null -> "KNOWN GAP"
                    r.passed -> "pass"
                    else -> "FAIL: " + r.failures.joinToString("; ")
                }
                appendLine("| ${r.scenario.id} | ${r.scenario.kind} | ${t.maxTier.level} | ${t.tierAtPayment?.level ?: "-"} | ${t.pauseLeadSeconds ?: "-"} | ${if (t.guardianSmsCount > 0) "yes" else "no"} | $res |")
            }
            val gaps = results.filter { it.scenario.knownGap != null }
            if (gaps.isNotEmpty()) {
                appendLine()
                appendLine("## Known gaps (roadmap, not gating)")
                gaps.forEach { appendLine("- **${it.scenario.id}**: ${it.scenario.knownGap}") }
            }
        }
    }
}
