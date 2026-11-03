package com.kavach.core.risk

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.Json

/**
 * The spec's "bank-side API hook" roadmap idea, made concrete: the phone can hand a bank or PSP a tiny, privacy-clean risk signal
 * so the other side of the payment can add a delay or a call-back on a high-risk transfer.
 *
 * Deliberately contains NO phone numbers, names, transcript or amounts; only the risk, the tier and which categories of signal fired.
 */
object BankSignal {
    const val VERSION = 1
    private val pretty = Json { prettyPrint = true }

    fun toJson(r: RiskResult, atMs: Long, pretty: Boolean = true): String {
        val obj = buildJsonObject {
            put("version", VERSION)
            put("generated_at_epoch_ms", atMs)
            put("risk", r.risk)
            put("tier", r.tier.name.lowercase())
            put("payment_in_progress", r.paymentIntent)
            put("hard_override", r.hardOverride?.name?.lowercase() ?: "none")
            put("signals", buildJsonArray { r.rules.sortedByDescending { it.points }.forEach { add(it.code.name.lowercase()) } })
            put("recommended_bank_action", when (r.tier) {
                Tier.NONE -> "none"
                Tier.NUDGE -> "none"
                Tier.PAUSE -> "delay_and_confirm"
                Tier.PAUSE_GUARDIAN -> "hold_and_call_back_customer"
            })
        }
        return if (pretty) this.pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj) else obj.toString()
    }
}
