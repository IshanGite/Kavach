package com.kavach.core.state

import com.kavach.core.config.RiskConfig
import com.kavach.core.model.*
import com.kavach.core.text.Extractors

data class TimelineEntry(val atMs: Long, val label: String)

/** Where the current payment intent came from. */
enum class IntentKind { PAYMENT_APP, COLLECT_REQUEST }

/**
 * Immutable snapshot of one suspicious episode. Built only by [reduce], so every number the risk
 * engine sees can be reproduced by replaying the same events.
 */
data class SessionState(
    val nowMs: Long = 0,
    // call
    val callLive: Boolean = false,
    val call: CallStarted? = null,
    val callEndedAtMs: Long? = null,
    val callDurationSec: Long? = null,
    // device level (survive across sessions)
    val remoteAccessApps: Set<String> = emptySet(),
    val smsFlaggedAtMs: Long? = null,
    // transcript and what was spoken
    val transcript: List<TranscriptChunk> = emptyList(),
    val spokenPayees: Set<String> = emptySet(),
    // audio
    val spoofScores: List<Double> = emptyList(),
    // payment
    val intent: PaymentIntent? = null,
    val intentKind: IntentKind? = null,
    val intentAtMs: Long? = null,
    val collectDuringCall: Boolean = false,
    val otpDuringCall: Boolean = false,
    val paymentProceededAtMs: Long? = null,
    val debitAfterIntentRupees: Double? = null,
    // language layer
    val findings: List<TacticsFindings> = emptyList(),
    val scamTypeSeen: ScamType = ScamType.NONE,
    val claimedIdentity: ClaimedIdentity = ClaimedIdentity.NONE,
    val evidenceQuotes: List<String> = emptyList(),
    // user / guardian feedback
    val questionsYesCount: Int = 0,
    val guardianHoldUntilMs: Long? = null,
    val timeline: List<TimelineEntry> = emptyList(),
) {
    fun callDurationMs(): Long =
        if (call == null) 0 else ((callEndedAtMs ?: nowMs) - call.atMs).coerceAtLeast(0)

    fun transcriptWordCount(): Int =
        transcript.sumOf { c -> c.text.trim().split(Regex("""\s+""")).count { it.isNotEmpty() } }

    fun transcriptText(windowMs: Long? = null): String {
        val from = if (windowMs == null) Long.MIN_VALUE else nowMs - windowMs
        return transcript.filter { it.atMs >= from }.joinToString(" ") { it.text }
    }

    /** The unknown-party rules (long call, payment during call...) only apply to someone not in contacts. */
    fun unknownCallLive(): Boolean = callLive && call != null && !call.isInContacts

    fun hasLivePaymentIntent(cfg: RiskConfig): Boolean {
        val at = intentAtMs ?: return false
        return intent != null && nowMs - at <= cfg.paymentIntentTtlSec * 1000L
    }

    companion object {
        /** New episode, keeping only device-level facts. */
        fun fresh(previous: SessionState?, atMs: Long) = SessionState(
            nowMs = atMs,
            remoteAccessApps = previous?.remoteAccessApps ?: emptySet(),
            smsFlaggedAtMs = previous?.smsFlaggedAtMs,
        )
    }
}

private const val MAX_TIMELINE = 400
private const val MAX_TRANSCRIPT_CHUNKS = 600

private fun SessionState.log(atMs: Long, label: String): SessionState =
    copy(timeline = (timeline + TimelineEntry(atMs, label)).takeLast(MAX_TIMELINE))

/** Pure state transition. The only place session state is ever changed. */
fun SessionState.reduce(e: KavachEvent, cfg: RiskConfig, isScamNumber: (String) -> Boolean = { false }): SessionState {
    val s = copy(nowMs = maxOf(nowMs, e.atMs))
    return when (e) {
        is CallStarted -> {
            val base = if (s.call != null && !s.callLive) SessionState.fresh(s, e.atMs) else s
            val tag = buildString {
                append("Call ${if (e.direction == CallDirection.INCOMING) "from" else "to"} ${e.contactName ?: e.number}")
                if (!e.isInContacts) append(" (not in contacts)")
                if (e.channel == CallChannel.VOIP) append(" [VoIP]")
                if (isScamNumber(e.number)) append(" [on scam list]")
            }
            base.copy(nowMs = maxOf(base.nowMs, e.atMs), callLive = true, call = e, callEndedAtMs = null, callDurationSec = null)
                .log(e.atMs, tag)
        }
        is CallEnded -> if (!s.callLive) s else
            s.copy(callLive = false, callEndedAtMs = e.atMs, callDurationSec = e.durationSec)
                .log(e.atMs, "Call ended after ${e.durationSec / 60} min ${e.durationSec % 60} s")
        is TranscriptChunk -> {
            val ids = Extractors.extractUpiIds(e.text) + Extractors.extractAccountNumbers(e.text)
            s.copy(
                transcript = (s.transcript + e).takeLast(MAX_TRANSCRIPT_CHUNKS),
                spokenPayees = s.spokenPayees + ids.map(Extractors::normalizePayee),
            ).let { if (ids.isNotEmpty()) it.log(e.atMs, "Payee detail spoken on call: ${ids.joinToString()}") else it }
        }
        is VoiceSpoofScore -> s.copy(spoofScores = (s.spoofScores + e.score.coerceIn(0.0, 1.0)).takeLast(5))
        is MessageReceived -> s // classified by the message analyst, which re-enters as MessageVerdict
        is MessageVerdict ->
            if (e.isScam && e.confidence >= 0.5) s.copy(smsFlaggedAtMs = e.atMs).log(e.atMs, "Suspicious message flagged: ${e.reason}") else s
        is OtpReceived ->
            if (s.unknownCallLive()) s.copy(otpDuringCall = true).log(e.atMs, "OTP received during call (${e.source})") else s
        is CollectRequest -> {
            val intent = PaymentIntent(e.atMs, "collect-request", PayStage.OPENED, e.amountRupees, e.payee)
            val flagged = if (s.unknownCallLive()) s.copy(collectDuringCall = true) else s
            flagged.copy(intent = intent, intentKind = IntentKind.COLLECT_REQUEST, intentAtMs = e.atMs)
                .log(e.atMs, "UPI collect request${e.amountRupees?.let { " of ₹${it.toLong()}" } ?: ""} received")
        }
        is DebitAlert ->
            if (s.intentAtMs != null) s.copy(debitAfterIntentRupees = e.amountRupees)
                .log(e.atMs, "Debit alert ₹${e.amountRupees.toLong()}") else s
        is PaymentIntent -> {
            val merged = mergeIntent(s.intent, e)
            s.copy(intent = merged, intentKind = IntentKind.PAYMENT_APP, intentAtMs = e.atMs)
                .log(e.atMs, "${e.app} ${e.stage.name.lowercase().replace('_', ' ')}" +
                    (merged.payee?.let { " payee=$it" } ?: "") + (merged.amountRupees?.let { " amount=₹${it.toLong()}" } ?: ""))
        }
        is PaymentDismissed -> s.copy(intent = null, intentKind = null, intentAtMs = null).log(e.atMs, "Payment screen closed")
        is RemoteAccessDetected -> s.copy(remoteAccessApps = s.remoteAccessApps + e.app).log(e.atMs, "Remote-access app active: ${e.app}")
        is RemoteAccessEnded -> s.copy(remoteAccessApps = s.remoteAccessApps - e.app)
        is FindingsReady -> s.withFindings(e.findings, cfg)
        is GuardianHold -> s.copy(guardianHoldUntilMs = e.atMs + cfg.guardianHoldExtendMin * 60_000L)
            .log(e.atMs, "Guardian replied HOLD (+${cfg.guardianHoldExtendMin} min)")
        is QuestionsAnswered -> s.copy(questionsYesCount = e.yesCount).log(e.atMs, "User answered pause questions (${e.yesCount} yes)")
        is UserProceeded -> s.copy(paymentProceededAtMs = e.atMs).log(e.atMs, "User chose to proceed with payment")
        is Tick -> s
    }
}

/** A later stage of the same payment keeps the amount/payee read earlier if the new frame lost them. */
private fun mergeIntent(old: PaymentIntent?, new: PaymentIntent): PaymentIntent {
    if (old == null || old.app != new.app) return new
    return new.copy(amountRupees = new.amountRupees ?: old.amountRupees, payee = new.payee ?: old.payee)
}

/**
 * Anti-hallucination: evidence quotes and payee details are kept only if they really appear in the
 * transcript. A small model (or an adversarial caller) cannot invent evidence or a fake payee match.
 */
private fun SessionState.withFindings(raw: TacticsFindings, cfg: RiskConfig): SessionState {
    val haystack = Extractors.normalizePayee(transcriptText())
    val quotes = raw.evidenceQuotes.filter { it.isNotBlank() && haystack.contains(Extractors.normalizePayee(it)) }
    val payees = raw.payeeDetailsMentioned.filter { it.isNotBlank() && haystack.contains(Extractors.normalizePayee(it)) }
    val clean = raw.copy(evidenceQuotes = quotes, payeeDetailsMentioned = payees, llmRisk = raw.llmRisk.coerceIn(0, 100))
    val history = (findings + clean).takeLast(maxOf(1, cfg.languageSmoothingRuns))
    return copy(
        findings = history,
        scamTypeSeen = if (clean.scamType != ScamType.NONE) clean.scamType else scamTypeSeen,
        claimedIdentity = if (clean.claimedIdentity != ClaimedIdentity.NONE) clean.claimedIdentity else claimedIdentity,
        evidenceQuotes = (evidenceQuotes + quotes).distinct().takeLast(12),
        spokenPayees = spokenPayees + payees.map(Extractors::normalizePayee),
    ).log(
        nowMs,
        "Language analysis (${raw.source}): risk ${clean.llmRisk}" + (if (clean.scamType != ScamType.NONE) ", ${clean.scamType.name.lowercase()}" else ""),
    )
}
