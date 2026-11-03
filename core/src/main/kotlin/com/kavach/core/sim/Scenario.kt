package com.kavach.core.sim

import com.kavach.core.baseline.InMemoryBaseline
import com.kavach.core.baseline.PastPayment
import com.kavach.core.intervention.TrustedContact
import com.kavach.core.intervention.UserProfile
import com.kavach.core.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A scripted episode: what the phone would sense, second by second, plus what Kavach is expected to do.
 * Plain JSON so anyone on the team can add one. They drive the CI evals AND the in-app "Simulate" backup demo.
 */
@Serializable
data class Scenario(
    val id: String,
    /** "scam" scenarios must be caught at the payment moment; "normal" scenarios must never get a pause. */
    val kind: String,
    val description: String = "",
    /** Local (IST) wall-clock start, controls the odd-hour rule. */
    @SerialName("start_local") val startLocal: String = "2026-11-03T15:00:00",
    val baseline: List<BaselineEntry>? = null,
    val events: List<ScenarioEvent>,
    val expect: Expect = Expect(),
    /** Known gap we have not built yet: reported on the scorecard, never gates CI. */
    @SerialName("known_gap") val knownGap: String? = null,
    /** Which analyst variant to run: "keywords" (default), "none" (audio dead), "obedient-zero" (a hijacked LLM), "slow". */
    val analyst: String = "keywords",
)

@Serializable
data class BaselineEntry(val payee: String, val amount: Double, val count: Int = 1)

@Serializable
data class ScenarioEvent(
    /** Seconds from scenario start. */
    val t: Int,
    val type: String,
    val number: String? = null,
    @SerialName("in_contacts") val inContacts: Boolean? = null,
    val name: String? = null,
    val channel: String? = null,
    val text: String? = null,
    val speaker: String? = null,
    val app: String? = null,
    val stage: String? = null,
    val amount: Double? = null,
    val payee: String? = null,
    val source: String? = null,
    val score: Double? = null,
    val yes: Int? = null,
    val from: String? = null,
    val duration: Int? = null,
)

@Serializable
data class Expect(
    /** Highest tier reached anywhere in the run must be at least / at most this. */
    @SerialName("min_tier") val minTier: Int? = null,
    @SerialName("max_tier") val maxTier: Int? = null,
    /** Highest tier from the first payment event onwards. */
    @SerialName("min_tier_at_payment") val minTierAtPayment: Int? = null,
    /** Highest tier BEFORE any payment event (e.g. no pause just for being on a call). */
    @SerialName("max_tier_before_payment") val maxTierBeforePayment: Int? = null,
    @SerialName("guardian_sms") val guardianSms: Boolean? = null,
    /** The pause overlay must already be showing when the PIN screen appears. */
    @SerialName("pause_before_pin") val pauseBeforePin: Boolean? = null,
    @SerialName("message_flagged") val messageFlagged: Boolean? = null,
)

object ScenarioLoader {
    private val json = Json { ignoreUnknownKeys = false }
    private val IST = ZoneId.of("Asia/Kolkata")

    fun parse(text: String): Scenario = json.decodeFromString(Scenario.serializer(), text)

    /** All bundled scenarios, via scenarios/index.json (listing a jar directory is not portable to Android). */
    fun loadBundled(): List<Scenario> {
        val cl = ScenarioLoader::class.java.classLoader
        fun read(path: String) = cl.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        val index = read("scenarios/index.json") ?: return emptyList()
        val names = Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), index)
        return names.map { n -> parse(read("scenarios/$n.json") ?: error("scenario listed in index but missing: $n")) }
    }

    fun startEpochMs(s: Scenario): Long = LocalDateTime.parse(s.startLocal).atZone(IST).toInstant().toEpochMilli()

    /** The user's payment history when a scenario does not bring its own. */
    fun defaultBaseline() = listOf(
        BaselineEntry("ramesh.kirana@ybl", 200.0, 6),
        BaselineEntry("sunita.vegetables@paytm", 120.0, 5),
        BaselineEntry("rahul.son@okhdfcbank", 1500.0, 4),
        BaselineEntry("milk.dairy@upi", 60.0, 8),
    )

    fun buildBaseline(s: Scenario): InMemoryBaseline {
        val entries = s.baseline ?: defaultBaseline()
        val t0 = startEpochMs(s) - 86_400_000L * 3
        return InMemoryBaseline(entries.flatMap { e -> (0 until e.count).map { PastPayment(e.payee, e.amount, t0 - it * 3_600_000L) } })
    }

    fun defaultProfile() = UserProfile(
        userName = "Papa",
        guardians = listOf(TrustedContact("Anjali", "+919800000001", "daughter")),
        familyContacts = listOf(TrustedContact("Rahul", "+919800000002", "son"), TrustedContact("Anjali", "+919800000001", "daughter")),
        familySafeWordSet = true,
    )

    fun toEvent(s: Scenario, e: ScenarioEvent): KavachEvent {
        val at = startEpochMs(s) + e.t * 1000L
        fun req(v: String?, what: String) = v ?: error("scenario ${s.id}: event '${e.type}' at t=${e.t} needs '$what'")
        return when (e.type) {
            "call_started" -> CallStarted(
                at, req(e.number, "number"),
                direction = CallDirection.INCOMING,
                channel = if (e.channel.equals("voip", true)) CallChannel.VOIP else CallChannel.CELLULAR,
                isInContacts = e.inContacts ?: false, contactName = e.name,
            )
            "call_ended" -> CallEnded(at, (e.duration ?: e.t).toLong())
            "transcript" -> TranscriptChunk(
                at, req(e.text, "text"), "auto",
                when (e.speaker?.lowercase()) { "caller" -> Speaker.CALLER; "user" -> Speaker.USER; else -> Speaker.UNKNOWN },
            )
            "spoof" -> VoiceSpoofScore(at, e.score ?: 0.0)
            "message" -> MessageReceived(at, e.source ?: "unknown", req(e.text, "text"))
            "payment" -> PaymentIntent(
                at, e.app ?: "PhonePe",
                when (e.stage?.lowercase()) { "amount_entered" -> PayStage.AMOUNT_ENTERED; "pin_screen" -> PayStage.PIN_SCREEN; else -> PayStage.OPENED },
                e.amount, e.payee,
            )
            "payment_dismissed" -> PaymentDismissed(at)
            "remote_access" -> RemoteAccessDetected(at, e.app ?: "AnyDesk")
            "remote_access_ended" -> RemoteAccessEnded(at, e.app ?: "AnyDesk")
            "collect" -> CollectRequest(at, e.amount, e.payee)
            "debit" -> DebitAlert(at, e.amount ?: 0.0)
            "questions" -> QuestionsAnswered(at, e.yes ?: 0)
            "proceed" -> UserProceeded(at)
            "guardian_hold" -> GuardianHold(at, req(e.from, "from"))
            else -> error("scenario ${s.id}: unknown event type '${e.type}'")
        }
    }
}
