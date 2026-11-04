package com.kavach.core.risk

import com.kavach.core.baseline.PaymentBaseline
import com.kavach.core.config.RiskConfig
import com.kavach.core.model.CallChannel
import com.kavach.core.model.ClaimedIdentity
import com.kavach.core.model.TacticsFindings
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
    REPEATED_NEW_PAYEES, ESCALATING_NEW_PAYEE_AMOUNTS, INSTITUTION_FROM_PERSONAL_MOBILE,
}

enum class HardOverride { REMOTE_ACCESS_WITH_PAYMENT, PAYEE_FROM_CALL }

enum class Guard {
    NO_PAYMENT_INTENT_CAPS_AT_NUDGE, SAVED_PAYEE_NO_CALL_CAPS_AT_ZERO,
    /** Raised R to the nudge line: real scam language on a live unknown call must never read as Safe (it never raises a tier past nudge). */
    EVIDENCE_FLOORS_AT_NUDGE,
}

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
        // What is "usual" is judged per payee once we know them (their own median), otherwise against the user's overall median.
        val payeeMedian = if (payee != null && baseline.paymentCount(payee) >= cfg.minPaymentsForPayeeMedian) baseline.medianAmountFor(payee) else null
        val median = payeeMedian ?: baseline.medianAmount()
        if (amount != null && median != null && baseline.historySize() >= cfg.minHistoryForMedian && median > 0 &&
            amount > cfg.amountMultipleOfMedian * median
        ) {
            rules += RuleHit(RuleCode.AMOUNT_ABOVE_USUAL, p.amountOver3xMedian, "${(amount / median).toInt()}x usual")
        }

        // Investment / task scams drip money to a series of new accounts, usually with no call at all.
        if (intent != null && payee != null && !knownPayee) {
            val windowStart = s.nowMs - cfg.newPayeeWindowHours * 3_600_000L
            val me = Extractors.normalizePayee(payee)
            val newOthers = s.payeeAttempts.filter { it.atMs >= windowStart && Extractors.normalizePayee(it.payee) != me }
                .filter { baseline.paymentCount(it.payee) == 0 && Extractors.normalizePayee(it.payee) !in trustedPayees() }
            if (newOthers.isNotEmpty()) {
                rules += RuleHit(RuleCode.REPEATED_NEW_PAYEES, p.repeatedNewPayees, "${newOthers.size + 1} new payees in ${cfg.newPayeeWindowHours} h")
                val prior = newOthers.mapNotNull { it.amountRupees }.maxOrNull()
                val usual = baseline.medianAmount()
                if (amount != null && prior != null && amount > prior && usual != null && usual > 0 && amount > cfg.amountMultipleOfMedian * usual) {
                    rules += RuleHit(RuleCode.ESCALATING_NEW_PAYEE_AMOUNTS, p.escalatingNewPayeeAmounts, "bigger than the earlier ones")
                }
            }
        }

        if ((unknown || intent != null) && isOddHour(s.nowMs)) rules += RuleHit(RuleCode.ODD_HOUR, p.oddHour)

        if (s.remoteAccessApps.isNotEmpty()) rules += RuleHit(RuleCode.REMOTE_ACCESS_ACTIVE, p.remoteAccessActive, s.remoteAccessApps.joinToString())

        if (unknown && (s.otpDuringCall || s.collectDuringCall)) rules += RuleHit(RuleCode.OTP_OR_COLLECT_DURING_CALL, p.otpOrCollectDuringCall)

        if (unknown && s.spoofScores.isNotEmpty() && s.spoofScores.takeLast(3).average() > cfg.spoofThreshold) {
            rules += RuleHit(RuleCode.SYNTHETIC_VOICE, p.syntheticVoice)
        }

        if (call != null && s.callLive && isScamNumber(call.number)) rules += RuleHit(RuleCode.NUMBER_ON_SCAM_LIST, p.numberOnScamList)

        // Both must hold: an institution finding from the transcript analyst, and a normal mobile.
        // Unknown-live-call is already +20. 140/160 series and saved contacts add nothing here.
        if (unknown && call != null && CallerNumber.isPersonalMobile(call.number) && claimsInstitution(s)) {
            rules += RuleHit(RuleCode.INSTITUTION_FROM_PERSONAL_MOBILE, p.institutionFromPersonalMobile, "ordinary mobile, not 140/160")
        }

        val smsAt = s.smsFlaggedAtMs
        if (smsAt != null && s.nowMs - smsAt in 0..DAY_MS && (unknown || intent != null)) {
            rules += RuleHit(RuleCode.RELATED_SMS_FLAGGED, p.relatedSmsFlagged)
        }

        val context = rules.sumOf { it.points }.coerceIn(0, 100)

        // L only exists once the analyst has actually run over a usable transcript (option D: silent audio => R = C).
        // Smoothing is fast-attack, slow-release: the analysis window is cumulative (the newest run already contains the
        // earlier text), so a plain average only drags a correct alarm down. A spike counts immediately; a dip after a
        // high is damped by the mean of the last few runs.
        // Evidence gate: L is only trusted over recognisable speech. With too few clean words (junk ASR from faint audio, one stray
        // word) L stays unknown unless the rules saw a real tactic, so noise can never read as "benign" and lower a scary context.
        val speech = s.speechEvidence(cfg.transcriptWindowSec * 1000L)
        val cleanWords = speech.clean
        val latest = s.findings.lastOrNull()
        val thin = cleanWords < cfg.minCleanWords || speech.cleanFraction < cfg.minCleanFraction
        val corroborated = latest != null && latest.tactics.maxScore() >= CORROBORATED_TACTIC
        val language: Int? =
            if (latest != null && cleanWords >= cfg.minTranscriptWords && (!thin || corroborated)) {
                val recent = s.findings.takeLast(cfg.languageSmoothingRuns).map { it.llmRisk }
                maxOf(recent.last(), recent.average().toInt())
            } else null

        val fused = if (language != null)
            (cfg.contextWeight * context + cfg.languageWeight * language).toInt().coerceIn(0, 100)
        else context
        // On thin evidence language may only add to the context, never pull it down.
        val fusedRisk = if (thin && language != null) maxOf(fused, context) else fused

        // Evidence floor: while an unknown call is live, recognisable speech with real tactic hits (a money ask, a threat, secrecy,
        // an OTP/remote-access ask, or two softer ones) is never "Safe", even when C and L are both low. It lifts R to the nudge line only,
        // so no pause without payment intent (guards and tiers above are untouched), and it needs a live call, so it vanishes at hang-up.
        val floored = cfg.evidenceFloorEnabled && unknown && !thin && latest != null && hasRealEvidence(latest) && fusedRisk < cfg.tiers.nudge
        val risk = if (floored) cfg.tiers.nudge else fusedRisk

        var tier = when {
            risk >= cfg.tiers.pauseGuardian -> Tier.PAUSE_GUARDIAN
            risk >= cfg.tiers.pause -> Tier.PAUSE
            risk >= cfg.tiers.nudge -> Tier.NUDGE
            else -> Tier.NONE
        }
        val guards = mutableListOf<Guard>()
        if (floored) guards += Guard.EVIDENCE_FLOORS_AT_NUDGE
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

    /**
     * Reuses the transcript finding. Does not call an analyst.
     * Bank, police and CBI are explicit labels. Other authorities (income tax, TRAI, a court, RBI)
     * land as OTHER once the authority lexicon matches. A family story or a courier label does not:
     * "police ne pakad liya" must not score.
     */
    private fun claimsInstitution(s: SessionState): Boolean = when (s.claimedIdentity) {
        ClaimedIdentity.BANK, ClaimedIdentity.POLICE, ClaimedIdentity.CBI_OFFICER -> true
        // One lexicon hit scores 0.65; the keyword floor keeps 0.85 of that (~0.55). 0.5 still
        // requires a real hit and ignores an uncorroborated model score, which is capped at 0.4.
        ClaimedIdentity.OTHER -> s.findings.any { it.tactics.authorityImpersonation >= AUTHORITY_CLAIM }
        else -> false
    }

    /**
     * The latest analysis (over the rolling window) found real language, not a model's guess: tactic scores are grounded (an uncorroborated model
     * score is capped at 0.4, below [cfg.evidenceFloorTacticMin]) and at least one evidence quote really appears in the transcript.
     * A hard tactic alone is enough. Urgency, authority or an emotional story only count together: "abhi" or "officer" alone is everyday speech.
     */
    private fun hasRealEvidence(f: TacticsFindings): Boolean {
        if (f.evidenceQuotes.isEmpty()) return false
        val t = f.tactics
        val min = cfg.evidenceFloorTacticMin
        val hard = maxOf(t.paymentOrTransferRequest, t.threatOfArrestOrLegalAction, t.secrecyOrIsolation, t.remoteAccessOrOtpRequest)
        if (hard >= min) return true
        return listOf(t.authorityImpersonation, t.urgencyOrDeadline, t.emotionalEmergency).count { it >= min } >= 2
    }

    private fun isOddHour(atMs: Long): Boolean {
        val h = Instant.ofEpochMilli(atMs).atZone(zone).hour
        return if (cfg.oddHourStart > cfg.oddHourEnd) h >= cfg.oddHourStart || h < cfg.oddHourEnd else h in cfg.oddHourStart until cfg.oddHourEnd
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val AUTHORITY_CLAIM = 0.5
        /** A tactic the rule lexicon really matched (one hit scores 0.65, the floor keeps ~0.55); an uncorroborated model score is capped at 0.4. */
        const val CORROBORATED_TACTIC = 0.5
    }
}
