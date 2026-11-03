package com.kavach.core.model

/**
 * Everything Kavach knows arrives as one of these typed events.
 * Sensors only emit events, analysts only produce findings, and only the risk engine decides.
 * All timestamps are epoch millis so scenarios replay deterministically.
 */
sealed interface KavachEvent {
    val atMs: Long
}

enum class CallDirection { INCOMING, OUTGOING }
enum class CallChannel { CELLULAR, VOIP }
enum class PayStage { OPENED, AMOUNT_ENTERED, PIN_SCREEN }
enum class Speaker { CALLER, USER, UNKNOWN }

data class CallStarted(
    override val atMs: Long,
    val number: String,
    val direction: CallDirection = CallDirection.INCOMING,
    val channel: CallChannel = CallChannel.CELLULAR,
    val isInContacts: Boolean = false,
    val contactName: String? = null,
    val isInternational: Boolean = false,
    val firstSeenToday: Boolean = true,
) : KavachEvent

data class CallEnded(override val atMs: Long, val durationSec: Long) : KavachEvent

data class TranscriptChunk(
    override val atMs: Long,
    val text: String,
    val language: String = "auto",
    val speaker: Speaker = Speaker.UNKNOWN,
) : KavachEvent

/** 0..1, higher = more likely synthetic. A weak supporting signal only. */
data class VoiceSpoofScore(override val atMs: Long, val score: Double) : KavachEvent

data class MessageReceived(override val atMs: Long, val source: String, val text: String) : KavachEvent

data class OtpReceived(override val atMs: Long, val source: String) : KavachEvent

data class CollectRequest(override val atMs: Long, val amountRupees: Double?, val payee: String?) : KavachEvent

data class DebitAlert(override val atMs: Long, val amountRupees: Double, val bank: String? = null) : KavachEvent

data class PaymentIntent(
    override val atMs: Long,
    val app: String,
    val stage: PayStage,
    val amountRupees: Double? = null,
    val payee: String? = null,
) : KavachEvent

/** The user left the payment screen (back / home / app closed). */
data class PaymentDismissed(override val atMs: Long) : KavachEvent

data class RemoteAccessDetected(override val atMs: Long, val app: String) : KavachEvent

data class RemoteAccessEnded(override val atMs: Long, val app: String) : KavachEvent

/** Output of the message analyst, fed back in as an event so everything is replayable. */
data class MessageVerdict(
    override val atMs: Long,
    val isScam: Boolean,
    val type: ScamType,
    val confidence: Double,
    val reason: String,
) : KavachEvent

/** Output of the transcript analyst (LLM and/or keyword rules), fed back in as an event. */
data class FindingsReady(override val atMs: Long, val findings: TacticsFindings) : KavachEvent

/** A guardian replied HOLD by SMS. `fromNumber` is verified against registered guardians. */
data class GuardianHold(override val atMs: Long, val fromNumber: String) : KavachEvent

/** Periodic clock tick so duration-based rules advance even when nothing else happens. */
data class Tick(override val atMs: Long) : KavachEvent

/** The user answered the pause-screen questions. `yesCount > 0` means the coercion was confirmed. */
data class QuestionsAnswered(override val atMs: Long, val yesCount: Int) : KavachEvent

/** The user proceeded with the payment after the pause (or dismissed a nudge). */
data class UserProceeded(override val atMs: Long) : KavachEvent

enum class ScamType {
    DIGITAL_ARREST, VOICE_CLONE_FAMILY, KYC_BANK, COURIER, REFUND, INVESTMENT, NONE;

    companion object {
        fun parse(raw: String?): ScamType {
            val k = raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: return NONE
            return entries.firstOrNull { it.name.lowercase() == k } ?: NONE
        }
    }
}

enum class ClaimedIdentity {
    CBI_OFFICER, POLICE, BANK, COURIER, FAMILY_MEMBER, OTHER, NONE;

    companion object {
        fun parse(raw: String?): ClaimedIdentity {
            val k = raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: return NONE
            return when {
                k.startsWith("cbi") -> CBI_OFFICER
                else -> entries.firstOrNull { it.name.lowercase() == k } ?: NONE
            }
        }
    }
}

/** Scores in 0..1 for each coercion trait, mirroring the Transcript Analyst JSON schema in the spec. */
data class Tactics(
    val authorityImpersonation: Double = 0.0,
    val threatOfArrestOrLegalAction: Double = 0.0,
    val urgencyOrDeadline: Double = 0.0,
    val secrecyOrIsolation: Double = 0.0,
    val paymentOrTransferRequest: Double = 0.0,
    val remoteAccessOrOtpRequest: Double = 0.0,
    val emotionalEmergency: Double = 0.0,
) {
    fun max(o: Tactics) = Tactics(
        maxOf(authorityImpersonation, o.authorityImpersonation),
        maxOf(threatOfArrestOrLegalAction, o.threatOfArrestOrLegalAction),
        maxOf(urgencyOrDeadline, o.urgencyOrDeadline),
        maxOf(secrecyOrIsolation, o.secrecyOrIsolation),
        maxOf(paymentOrTransferRequest, o.paymentOrTransferRequest),
        maxOf(remoteAccessOrOtpRequest, o.remoteAccessOrOtpRequest),
        maxOf(emotionalEmergency, o.emotionalEmergency),
    )
}

data class TacticsFindings(
    val claimedIdentity: ClaimedIdentity = ClaimedIdentity.NONE,
    val tactics: Tactics = Tactics(),
    val scamType: ScamType = ScamType.NONE,
    /** 0..100 */
    val llmRisk: Int = 0,
    val evidenceQuotes: List<String> = emptyList(),
    val payeeDetailsMentioned: List<String> = emptyList(),
    /** Which analyst produced this, for the settings screen / demo ("ollama:gemma3:4b", "keywords"...). */
    val source: String = "none",
)
