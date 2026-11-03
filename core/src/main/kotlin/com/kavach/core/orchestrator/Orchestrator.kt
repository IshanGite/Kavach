package com.kavach.core.orchestrator

import com.kavach.core.baseline.PaymentBaseline
import com.kavach.core.baseline.PastPayment
import com.kavach.core.config.RiskConfig
import com.kavach.core.intervention.*
import com.kavach.core.model.*
import com.kavach.core.risk.RiskEngine
import com.kavach.core.risk.RiskResult
import com.kavach.core.risk.Tier
import com.kavach.core.state.SessionState
import com.kavach.core.state.reduce
import com.kavach.core.text.Extractors
import java.time.ZoneId

enum class OrchestratorState { IDLE, MONITORING, ELEVATED, INTERVENING, POST_INCIDENT }

/** Things the Android layer must do. The core never touches the OS; it only asks. */
sealed interface Effect {
    data class ShowNudge(val plan: InterventionPlan) : Effect
    data class ShowPause(val plan: InterventionPlan) : Effect
    data class SpeakWarning(val text: String, val langTag: String) : Effect
    data class SendGuardianSms(val numbers: List<String>, val text: String) : Effect
    data class ExtendPause(val untilMs: Long) : Effect
    data class CoercionConfirmed(val message: String) : Effect
    data object DismissOverlay : Effect
    /** Call ended after an elevated session: offer a check-in; build the report if the user says so. */
    data class OfferCheckIn(val sawPaymentAttempt: Boolean) : Effect
    /** A debit landed during/after a high-risk session: show 1930 and pre-fill the complaint immediately. */
    data object GoldenHour : Effect
}

data class Decision(
    val atMs: Long,
    val state: OrchestratorState,
    val result: RiskResult,
    /** The tier the UI should be showing right now (latched while a payment is in flight). */
    val tier: Tier,
    val plan: InterventionPlan?,
    val effects: List<Effect>,
)

/** What the transcript analyst should look at, produced by [Orchestrator.nextAnalysisRequest]. */
data class AnalysisRequest(
    val atMs: Long,
    val windowText: String,
    val callDurationMin: Int,
    val callerInContacts: Boolean,
    val channel: CallChannel,
)

/**
 * One state machine per device. Single-threaded by contract: the runtime feeds it events through one
 * queue, so decisions are deterministic and replayable.
 */
class Orchestrator(
    private val cfg: RiskConfig,
    private val baseline: PaymentBaseline,
    var profile: UserProfile = UserProfile(),
    private val isScamNumber: (String) -> Boolean = { false },
    zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    private val speakNudges: Boolean = false,
) {
    private val dismissals = mutableMapOf<String, Int>()
    private val trusted = mutableSetOf<String>()
    private val engine = RiskEngine(cfg, baseline, isScamNumber, zone) { trusted }
    private val planner = InterventionPlanner(cfg)

    var session: SessionState = SessionState()
        private set
    var state: OrchestratorState = OrchestratorState.IDLE
        private set
    val closedSessions = mutableListOf<SessionState>()

    private var latched: Tier = Tier.NONE
    private var shownTier: Tier = Tier.NONE
    private var nudgedIntentAt: Long? = null
    private var nudgedThisSession = false
    private var lastGuardianAlertMs: Long? = null
    private var sessionMaxTier: Tier = Tier.NONE
    private var lastAnalysisMs: Long? = null
    private var analyzedWords = 0
    private var pauseUntilMs: Long? = null

    fun trustedPayees(): Set<String> = trusted.toSet()

    fun onEvent(e: KavachEvent): Decision {
        // Guardian HOLD is authenticated before it can touch state.
        if (e is GuardianHold && !GuardianLink.isFromGuardian(e.fromNumber, profile)) {
            return decide(e.atMs, emptyList())
        }
        if (e is CallStarted && !session.callLive && session.call != null) archiveAndReset(e.atMs)

        val before = session
        session = session.reduce(e, cfg, isScamNumber)
        val effects = mutableListOf<Effect>()

        when (e) {
            is GuardianHold -> {
                val until = e.atMs + cfg.guardianHoldExtendMin * 60_000L
                pauseUntilMs = maxOf(pauseUntilMs ?: 0L, until)
                effects += Effect.ExtendPause(pauseUntilMs!!)
            }
            is QuestionsAnswered -> if (e.yesCount > 0) effects += Effect.CoercionConfirmed(T.ifAnyYes(profile.language))
            is UserProceeded -> {
                if (shownTier == Tier.NUDGE) learnDismissal(before)
            }
            is PaymentDismissed -> {
                if (shownTier != Tier.NONE) effects += Effect.DismissOverlay
                latched = Tier.NONE
                shownTier = Tier.NONE
                nudgedIntentAt = null
            }
            is DebitAlert -> if (sessionMaxTier.level >= Tier.PAUSE.level && session.intentAtMs != null) effects += Effect.GoldenHour
            is PaymentIntent, is CollectRequest -> {
                // A fresh payment intent starts a fresh latch (the old one belonged to the previous payment).
                if (before.intentAtMs == null || (before.intent?.app != session.intent?.app)) {
                    latched = Tier.NONE
                    shownTier = if (shownTier.level >= Tier.PAUSE.level) shownTier else Tier.NONE
                }
            }
            else -> Unit
        }
        if (e is CallEnded) {
            if (sessionMaxTier.level >= Tier.NUDGE.level) {
                effects += Effect.OfferCheckIn(sawPaymentAttempt = session.intentAtMs != null)
            }
        }
        return decide(e.atMs, effects)
    }

    private fun decide(atMs: Long, pre: List<Effect>): Decision {
        val effects = pre.toMutableList()
        val result = engine.evaluate(session)
        val nowTier = result.tier

        // Latch pauses while a payment intent is live: the overlay must not flicker off mid-payment because a
        // later frame lost the payee or the LLM run came back lower.
        if (result.paymentIntent && nowTier.level >= Tier.PAUSE.level) latched = maxOf(latched, nowTier)
        if (!result.paymentIntent && !session.hasLivePaymentIntent(cfg)) latched = Tier.NONE
        val tier = if (result.paymentIntent) maxOf(nowTier, latched) else nowTier
        if (tier.level > sessionMaxTier.level) sessionMaxTier = tier

        val plan = if (tier == Tier.NONE) null else planner.plan(tier, result, session, profile)
        val payeeKey = session.intent?.payee?.let(Extractors::normalizePayee)

        when {
            tier.level >= Tier.PAUSE.level && tier.level > shownTier.level -> {
                effects += Effect.ShowPause(plan!!)
                plan.spokenWarning?.let { effects += Effect.SpeakWarning(it, profile.language.tag) }
                shownTier = tier
                nudgedThisSession = true
                pauseUntilMs = atMs + plan.cooldownSeconds * 1000L
                if (tier == Tier.PAUSE_GUARDIAN) maybeAlertGuardians(atMs, result, effects)
            }
            tier == Tier.NUDGE && shownTier == Tier.NONE -> {
                val intentAt = session.intentAtMs
                val suppressed = payeeKey != null && payeeKey in trusted
                val firstNudge = !nudgedThisSession
                val newPayScreen = intentAt != null && intentAt != nudgedIntentAt
                // A pay-screen nudge waits until the payee (or a later stage) is known, so a payee the user already
                // dismissed twice can be recognised and left alone. It still lands well before the PIN screen.
                val intent = session.intent
                val intentReady = intent == null || intent.payee != null || intent.stage != PayStage.OPENED
                if (!suppressed && intentReady && (firstNudge || newPayScreen)) {
                    effects += Effect.ShowNudge(plan!!)
                    if (speakNudges) plan.spokenWarning?.let { effects += Effect.SpeakWarning(it, profile.language.tag) }
                    shownTier = Tier.NUDGE
                    nudgedThisSession = true
                    nudgedIntentAt = intentAt
                }
            }
            tier == Tier.NONE && shownTier != Tier.NONE && !session.hasLivePaymentIntent(cfg) -> {
                effects += Effect.DismissOverlay
                shownTier = Tier.NONE
            }
        }

        state = deriveState(tier)
        return Decision(atMs, state, result, tier, plan, effects)
    }

    private fun maybeAlertGuardians(atMs: Long, result: RiskResult, effects: MutableList<Effect>) {
        val last = lastGuardianAlertMs
        if (last != null && atMs - last < GUARDIAN_COOLDOWN_MS) return
        val numbers = profile.guardians.map { it.number }.filter { it.isNotBlank() }
        if (numbers.isEmpty()) return
        lastGuardianAlertMs = atMs
        effects += Effect.SendGuardianSms(numbers, GuardianLink.compose(session, result, profile))
    }

    private fun deriveState(tier: Tier): OrchestratorState = when {
        tier.level >= Tier.PAUSE.level -> OrchestratorState.INTERVENING
        tier == Tier.NUDGE -> OrchestratorState.ELEVATED
        session.unknownCallLive() -> OrchestratorState.MONITORING
        !session.callLive && session.call != null && sessionMaxTier.level >= Tier.NUDGE.level -> OrchestratorState.POST_INCIDENT
        else -> OrchestratorState.IDLE
    }

    private fun learnDismissal(before: SessionState) {
        val key = (session.intent ?: before.intent)?.payee?.let(Extractors::normalizePayee) ?: return
        val n = (dismissals[key] ?: 0) + 1
        dismissals[key] = n
        if (n >= cfg.trustAfterDismissals) trusted += key
    }

    private fun archiveAndReset(atMs: Long) {
        closedSessions += session
        session = SessionState.fresh(session, atMs)
        latched = Tier.NONE
        shownTier = Tier.NONE
        nudgedThisSession = false
        nudgedIntentAt = null
        sessionMaxTier = Tier.NONE
        lastAnalysisMs = null
        analyzedWords = 0
        pauseUntilMs = null
        state = OrchestratorState.IDLE
    }

    /** True while a pause is in force (cooling-off timer or guardian HOLD). The UI keeps Proceed disabled until then. */
    fun pauseActiveAt(nowMs: Long): Boolean = (pauseUntilMs ?: 0L) > nowMs && shownTier.level >= Tier.PAUSE.level

    fun pauseUntil(): Long? = pauseUntilMs

    /**
     * The Transcript Analyst runs every [RiskConfig.analysisIntervalSec] while an unknown call is live and there is
     * new transcript. Returns null when nothing is due. Marks the request as taken.
     */
    fun nextAnalysisRequest(nowMs: Long): AnalysisRequest? {
        if (!session.unknownCallLive()) return null
        val words = session.transcriptWordCount()
        if (words == 0 || words == analyzedWords) return null
        val last = lastAnalysisMs
        if (last != null && nowMs - last < cfg.analysisIntervalSec * 1000L) return null
        lastAnalysisMs = nowMs
        analyzedWords = words
        val call = session.call!!
        val windowMs = cfg.transcriptWindowSec * 1000L
        return AnalysisRequest(
            atMs = nowMs,
            windowText = session.copy(nowMs = nowMs).transcriptText(windowMs),
            callDurationMin = (session.callDurationMs() / 60_000).toInt(),
            callerInContacts = call.isInContacts,
            channel = call.channel,
        )
    }

    /** Called by the app when a debit SMS or observed payment completes, to keep the baseline honest. */
    fun recordCompletedPayment(payee: String, amountRupees: Double, atMs: Long) {
        baseline.record(PastPayment(payee, amountRupees, atMs))
    }

    /** Session to report on: the live one if it is elevated, otherwise the most recent closed one. */
    fun reportableSession(): SessionState? =
        if (session.call != null || session.intent != null) session else closedSessions.lastOrNull()

    private companion object {
        const val GUARDIAN_COOLDOWN_MS = 10 * 60_000L
    }
}
