package com.kavach.core.analysis

import com.kavach.core.model.*
import kotlinx.serialization.json.*

/**
 * Turns whatever a small local model emits into a safe [TacticsFindings], or null if it is unusable.
 * Models wrap JSON in fences, return numbers as strings, invent enum values and omit fields; none of
 * that may crash the pipeline or push a score out of range.
 */
object FindingsParser {
    private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }

    fun extractJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { lenient.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { p ->
        p.doubleOrNull ?: p.contentOrNull?.trim()?.removeSuffix("%")?.toDoubleOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        is JsonPrimitive -> listOfNotNull(v.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
        else -> emptyList()
    }

    private fun unit(v: Double?): Double = (v ?: 0.0).let { if (it.isNaN()) 0.0 else it }.coerceIn(0.0, 1.0)

    /** Terse schema: "t":[authority,threat,urgency,secrecy,payment,otp_or_remote,emotional]. */
    private fun terseTactics(a: JsonArray): Tactics? {
        if (a.size < 7) return null
        val v = a.map { unit((it as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() }) }
        return Tactics(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }

    fun parseFindings(raw: String, source: String): TacticsFindings? {
        val obj = extractJsonObject(raw) ?: return null
        val terse = (obj["t"] as? JsonArray)?.let(::terseTactics)
        val t = obj["tactics"] as? JsonObject
        val riskKey = if (obj["llm_risk"] != null) "llm_risk" else "risk"
        // A response with neither tactics nor a risk number is not an analysis.
        if (t == null && terse == null && obj.num(riskKey) == null) return null
        val tactics = terse ?: Tactics(
            authorityImpersonation = unit(t?.num("authority_impersonation")),
            threatOfArrestOrLegalAction = unit(t?.num("threat_of_arrest_or_legal_action")),
            urgencyOrDeadline = unit(t?.num("urgency_or_deadline")),
            secrecyOrIsolation = unit(t?.num("secrecy_or_isolation")),
            paymentOrTransferRequest = unit(t?.num("payment_or_transfer_request")),
            remoteAccessOrOtpRequest = unit(t?.num("remote_access_or_otp_request")),
            emotionalEmergency = unit(t?.num("emotional_emergency")),
        )
        // Models sometimes answer on a 0..1 scale despite the prompt; a value strictly between 0 and 1 is a fraction.
        val risk = obj.num(riskKey)?.let { if (it > 0.0 && it < 1.0) it * 100 else it }
        return TacticsFindings(
            claimedIdentity = ClaimedIdentity.parse(obj.str("claimed_identity") ?: obj.str("id")),
            tactics = tactics,
            scamType = ScamType.parse(obj.str("scam_type") ?: obj.str("type")),
            llmRisk = (risk ?: 0.0).let { if (it.isNaN()) 0.0 else it }.coerceIn(0.0, 100.0).toInt(),
            evidenceQuotes = (if (obj["evidence_quotes"] != null) obj.strings("evidence_quotes") else obj.strings("q")).take(8),
            payeeDetailsMentioned = (if (obj["payee_details_mentioned"] != null) obj.strings("payee_details_mentioned") else obj.strings("p")).take(8),
            source = source,
        )
    }

    fun parseMessageVerdict(raw: String, atMs: Long): MessageVerdict? {
        val obj = extractJsonObject(raw) ?: return null
        val isScam = (obj["is_scam"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.let { s -> s == "true" || s == "yes" } }
            ?: return null
        return MessageVerdict(
            atMs = atMs,
            isScam = isScam,
            type = ScamType.parse(obj.str("type")),
            confidence = unit(obj.num("confidence")),
            reason = (obj.str("reason") ?: "").take(160),
        )
    }
}
