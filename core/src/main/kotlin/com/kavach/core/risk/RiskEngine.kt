package com.kavach.core.risk

import com.kavach.core.baseline.PaymentBaseline
import com.kavach.core.config.RiskConfig
import com.kavach.core.model.CallChannel
import com.kavach.core.state.IntentKind
import com.kavach.core.state.SessionState
import com.kavach.core.text.Extractors
import java.time.Instant
import java.time.ZoneId

enum class Tier(val level: Int) {
    NONE(0), NUDGE(1), PAUSE(2), PAUSE_GUARDIAN(3);

    companion object {
        fun of(level: Int) = entries.first { it.level == level.coerceIn(0, 3) }
    }
}

enum class RuleCode {
    UNKNOWN_LIVE_CALL, LONG_CALL, UNKNOWN_VOIP_OR_VIDEO, PAYMENT_APP_DURING_CALL, NEW_PAYEE, AMOUNT_ABOVE_USUAL,
    ODD_HOUR, REMOTE_ACCESS_ACTIVE, OTP_OR_COLLECT_DURING_CALL, SYNTHETIC_VOICE, NUMBER_ON_SCAM_LIST, RELATED_SMS_FLAGGED,
}

enum class HardOverride { REMOTE_ACCESS_WITH_PAYMENT, PAYEE_FROM_CALL }

enum class Guard { NO_PAYMENT_INTENT_CAPS_AT_NUDGE, SAVED_PAYEE_NO_CALL_CAPS_AT_ZERO }

data class RuleHit(val code: RuleCode, val points: Int, val detail: String? = null)

data class RiskResult(
    /** C: rule points, 0..100. Instant, never waits on an LLM. */
    val contextScore: Int,
    /** L: smoothed transcript-analyst risk, or null when there is no usable transcript yet. */
    val languageScore: Int?,
    /** R: final fused risk, 0..100. */
    val risk: Int,
    val tier: Tier,
    val rules: List<RuleHit>,
    val hardOverride: HardOverride?,
    val guards: List<Guard>,
    val paymentIntent: Boolean,
) {
    fun reasonCodes(): List<RuleCode> = rules.sortedByDescending { it.points }.map { it.code }
}

/**
 * The LLM interprets, deterministic code decides. This is the only place a tier is produced.
 * Pure function of ([SessionState], config, baseline): same inputs, same answer, always.
 */
class RiskEngine(
    private val cfg: RiskConfig,
    private val baseline: PaymentBaseline,
    private val isScamNumber: (String) -> Boolean = { false },
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    private val trustedPayees: () -> Set<String> = { emptySet() },
) {
    fun evaluate(s: SessionState): RiskResult {
        val p = cfg.points
        val rules = mutableListOf<RuleHit>()
        val unknown = s.unknownCallLive()
        val call = s.call
        val intentLive = s.hasLivePaymentIntent(cfg)
        val intent = if (intentLive) s.intent else null
        val payee = intent?.payee

        if (unknown) rules += RuleHit(RuleCode.UNKNOWN_LIVE_CALL, p.unknownLiveCall)

        if (unknown) {
            val min = s.callDurationMs() / 60_000.0
            when {
                min >= cfg.veryLongCallMin -> rules += RuleHit(RuleCode.LONG_CALL, p.callOver30Min, "over ${cfg.veryLongCallMin} min")
                min >= cfg.longCallMin -> rules += RuleHit(RuleCode.LONG_CALL, p.callOver10Min, "over ${cfg.longCallMin} min")
            }
            if (call?.channel == CallChannel.VOIP) rules += RuleHit(RuleCode.UNKNOWN_VOIP_OR_VIDEO, p.unknownVoip)
        }

        if (unknown && intent != null && s.intentKind == IntentKind.PAYMENT_APP) {
            rules += RuleHit(RuleCode.PAYMENT_APP_DURING_CALL, p.paymentAppDuringCall, intent.app)
        }

        val knownPayee = payee != null && (baseline.paymentCount(payee) > 0 || Extractors.normalizePayee(payee) in trustedPayees())
        if (intent != null && payee != null && !knownPayee) rules += RuleHit(RuleCode.NEW_PAYEE, p.newPayee, payee)

        val amount = intent?.amountRupees
        val median = baseline.medianAmount()
        if (amount != null && median != null && baseline.historySize() >= cfg.minHistoryForMedian &&
            amount > cfg.amountMultipleOfMedian * median
        ) {
            rules += RuleHit(RuleCode.AMOUNT_ABOVE_USUAL, p.amountOver3xMedian, "${(amount / median).toInt()}x usual")
        }

        if ((unknown || intent != null) && isOddHour(s.nowMs)) rules += RuleHit(RuleCode.ODD_HOUR, p.oddHour)

        if (s.remoteAccessApps.isNotEmpty()) rules += RuleHit(RuleCode.REMOTE_ACCESS_ACTIVE, p.remoteAccessActive, s.remoteAccessApps.joinToString())

        if (unknown && (s.otpDuringCall || s.collectDuringCall)) rules += RuleHit(RuleCode.OTP_OR_COLLECT_DURING_CALL, p.otpOrCollectDuringCall)

        if (unknown && s.spoofScores.isNotEmpty() && s.spoofScores.takeLast(3).average() > cfg.spoofThreshold) {
            rules += RuleHit(RuleCode.SYNTHETIC_VOICE, p.syntheticVoice)
        }

        if (call != null && s.callLive && isScamNumber(call.number)) rules += RuleHit(RuleCode.NUMBER_ON_SCAM_LIST, p.numberOnScamList)

        val smsAt = s.smsFlaggedAtMs
        if (smsAt != null && s.nowMs - smsAt in 0..DAY_MS && (unknown || intent != null)) {
            rules += RuleHit(RuleCode.RELATED_SMS_FLAGGED, p.relatedSmsFlagged)
        }

        val context = rules.sumOf { it.points }.coerceIn(0, 100)

        // L only exists once the analyst has actually run over a usable transcript (option D: silent audio => R = C).
        // Smoothing is fast-attack, slow-release: the analysis window is cumulative (the newest run already contains the
        // earlier text), so a plain average only drags a correct alarm down. A spike counts immediately; a dip after a
        // high is damped by the mean of the last few runs.
        val language: Int? =
            if (s.findings.isNotEmpty() && s.transcriptWordCount() >= cfg.minTranscriptWords) {
                val recent = s.findings.takeLast(cfg.languageSmoothingRuns).map { it.llmRisk }
                maxOf(recent.last(), recent.average().toInt())
            } else null

        val risk = if (language != null)
            (cfg.contextWeight * context + cfg.languageWeight * language).toInt().coerceIn(0, 100)
        else context

        var tier = when {
            risk >= cfg.tiers.pauseGuardian -> Tier.PAUSE_GUARDIAN
            risk >= cfg.tiers.pause -> Tier.PAUSE
            risk >= cfg.tiers.nudge -> Tier.NUDGE
            else -> Tier.NONE
        }
        val guards = mutableListOf<Guard>()
        val hasIntent = intent != null

        // Guard 1: friction on the payment screen only exists when a payment is actually starting.
        if (!hasIntent && tier.level > Tier.NUDGE.level) {
            tier = Tier.NUDGE
            guards += Guard.NO_PAYMENT_INTENT_CAPS_AT_NUDGE
        }
        // Guard 2: paying a saved, frequently paid payee with no live call is never interrupted.
        val savedFrequent = payee != null && baseline.paymentCount(payee) >= cfg.frequentPayeeMinPayments
        if (savedFrequent && !s.callLive && tier != Tier.NONE) {
            tier = Tier.NONE
            guards += Guard.SAVED_PAYEE_NO_CALL_CAPS_AT_ZERO
        }

        // Hard overrides beat the guards: the tripwire and the dictated-payee link are near-certain coercion.
        val override = when {
            hasIntent && s.remoteAccessApps.isNotEmpty() -> HardOverride.REMOTE_ACCESS_WITH_PAYMENT
            hasIntent && Extractors.payeeMatchesSpoken(payee, s.spokenPayees) -> HardOverride.PAYEE_FROM_CALL
            else -> null
        }
        if (override != null) tier = Tier.PAUSE_GUARDIAN

        return RiskResult(context, language, risk, tier, rules, override, guards, hasIntent)
    }

    private fun isOddHour(atMs: Long): Boolean {
        val h = Instant.ofEpochMilli(atMs).atZone(zone).hour
        return if (cfg.oddHourStart > cfg.oddHourEnd) h >= cfg.oddHourStart || h < cfg.oddHourEnd else h in cfg.oddHourStart until cfg.oddHourEnd
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
