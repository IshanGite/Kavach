package com.kavach.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every weight lives in risk_config.json so it can be tuned on stage without rebuilding.
 * Defaults mirror the spec's risk-fusion table exactly; a test pins that.
 */
@Serializable
data class RiskConfig(
    val points: Points = Points(),
    val tiers: Tiers = Tiers(),
    @SerialName("context_weight") val contextWeight: Double = 0.6,
    @SerialName("language_weight") val languageWeight: Double = 0.4,
    /** The latest N analyst runs are averaged into L. */
    @SerialName("language_smoothing_runs") val languageSmoothingRuns: Int = 3,
    /** A transcript only "exists" for fusion once it has at least this many words. */
    @SerialName("min_transcript_words") val minTranscriptWords: Int = 6,
    /**
     * L may only exist from recognisable speech: at least this many clean words in the rolling window. Below it, L is unknown
     * (R = C, as with no transcript) unless the rules saw a real tactic, because junk ASR must never read as "benign".
     */
    @SerialName("min_clean_words") val minCleanWords: Int = 12,
    /** ...and those clean words must be at least this share of all tokens, so a few real words inside a pile of junk do not count. */
    @SerialName("min_clean_fraction") val minCleanFraction: Double = 0.6,
    @SerialName("transcript_window_sec") val transcriptWindowSec: Int = 90,
    @SerialName("analysis_interval_sec") val analysisIntervalSec: Int = 20,
    /** How long a payment intent stays "live" without a fresh payment event. */
    @SerialName("payment_intent_ttl_sec") val paymentIntentTtlSec: Int = 300,
    @SerialName("long_call_min") val longCallMin: Int = 10,
    @SerialName("very_long_call_min") val veryLongCallMin: Int = 30,
    @SerialName("amount_multiple_of_median") val amountMultipleOfMedian: Double = 3.0,
    /** Need at least this many past payments before "amount vs median" is meaningful. */
    @SerialName("min_history_for_median") val minHistoryForMedian: Int = 3,
    @SerialName("odd_hour_start") val oddHourStart: Int = 23,
    @SerialName("odd_hour_end") val oddHourEnd: Int = 6,
    @SerialName("spoof_threshold") val spoofThreshold: Double = 0.7,
    /** A payee paid at least this many times counts as "saved, frequently paid". */
    /** A payee's own median is used instead of the global one once it has this many payments. */
    @SerialName("min_payments_for_payee_median") val minPaymentsForPayeeMedian: Int = 2,
    @SerialName("new_payee_window_hours") val newPayeeWindowHours: Int = 24,
    @SerialName("frequent_payee_min_payments") val frequentPayeeMinPayments: Int = 3,
    @SerialName("pause_seconds_tier2") val pauseSecondsTier2: Int = 30,
    @SerialName("pause_seconds_tier3") val pauseSecondsTier3: Int = 120,
    @SerialName("guardian_hold_extend_min") val guardianHoldExtendMin: Int = 10,
    /** Dismissing a nudge for the same payee this many times marks it trusted. */
    @SerialName("trust_after_dismissals") val trustAfterDismissals: Int = 2,
    /** Keyword analyst floor: LLM output can never push language risk below this fraction of the keyword score. */
    @SerialName("keyword_floor_fraction") val keywordFloorFraction: Double = 0.85,
) {
    @Serializable
    data class Points(
        @SerialName("unknown_live_call") val unknownLiveCall: Int = 20,
        @SerialName("call_over_10min") val callOver10Min: Int = 10,
        @SerialName("call_over_30min") val callOver30Min: Int = 15,
        @SerialName("unknown_voip_or_video") val unknownVoip: Int = 15,
        @SerialName("payment_app_during_call") val paymentAppDuringCall: Int = 25,
        @SerialName("new_payee") val newPayee: Int = 10,
        @SerialName("amount_over_3x_median") val amountOver3xMedian: Int = 10,
        @SerialName("odd_hour") val oddHour: Int = 5,
        @SerialName("remote_access_active") val remoteAccessActive: Int = 25,
        @SerialName("otp_or_collect_during_call") val otpOrCollectDuringCall: Int = 10,
        @SerialName("synthetic_voice") val syntheticVoice: Int = 10,
        @SerialName("number_on_scam_list") val numberOnScamList: Int = 40,
        @SerialName("related_sms_flagged") val relatedSmsFlagged: Int = 10,
        /** Two or more distinct never-paid payees attempted within 24 h (investment / task scams drip money to new accounts). */
        @SerialName("repeated_new_payees") val repeatedNewPayees: Int = 20,
        /** ...and this attempt is larger than the earlier ones and well above the user's usual amount (escalating deposits). */
        @SerialName("escalating_new_payee_amounts") val escalatingNewPayeeAmounts: Int = 10,
        /**
         * Unknown caller claims a bank, the police, or another authority from a normal mobile, not a 140/160 series number.
         * Small on purpose: unknown-live-call is already +20, and this must not carry a normal payment to a pause by itself.
         */
        @SerialName("institution_from_personal_mobile") val institutionFromPersonalMobile: Int = 10,
    )

    @Serializable
    data class Tiers(
        @SerialName("nudge") val nudge: Int = 35,
        @SerialName("pause") val pause: Int = 60,
        @SerialName("pause_guardian") val pauseGuardian: Int = 80,
    )

    companion object {
        // Strict on read: a typo in a tuned key must fail loudly, not silently fall back to a default on stage.
        private val strict = Json { ignoreUnknownKeys = false; isLenient = false }
        private val pretty = Json { prettyPrint = true; encodeDefaults = true }

        fun fromJson(text: String): RiskConfig = strict.decodeFromString(serializer(), text)

        fun toJson(c: RiskConfig): String = pretty.encodeToString(serializer(), c)

        /** Loads the bundled default config from the classpath. */
        fun loadDefault(): RiskConfig {
            val stream = RiskConfig::class.java.classLoader.getResourceAsStream("risk_config.json")
                ?: return RiskConfig()
            return stream.bufferedReader(Charsets.UTF_8).use { fromJson(it.readText()) }
        }
    }
}
