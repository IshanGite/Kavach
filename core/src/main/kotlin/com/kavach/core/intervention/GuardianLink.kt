package com.kavach.core.intervention

import com.kavach.core.model.ClaimedIdentity
import com.kavach.core.risk.RiskResult
import com.kavach.core.state.SessionState
import com.kavach.core.text.Extractors

/**
 * Guardian alerts are plain SMS (works with no internet or backend) and carry only a short summary:
 * never a transcript. HOLD replies are accepted only from registered guardian numbers.
 */
object GuardianLink {
    private val HOLD = Regex("""^\s*hold\b""", RegexOption.IGNORE_CASE)

    fun compose(s: SessionState, r: RiskResult, profile: UserProfile): String {
        val mins = (s.callDurationMs() / 60_000).toInt().coerceAtLeast(1)
        val who = when (s.claimedIdentity) {
            ClaimedIdentity.CBI_OFFICER -> "CBI"
            ClaimedIdentity.POLICE -> "the police"
            ClaimedIdentity.BANK -> "their bank"
            ClaimedIdentity.COURIER -> "a courier/customs officer"
            ClaimedIdentity.FAMILY_MEMBER -> "a relative"
            else -> null
        }
        val intent = s.intent
        val amount = intent?.amountRupees?.let { " to pay ₹${formatRupees(it)}" } ?: " to pay"
        val app = intent?.app?.takeIf { it.isNotBlank() && it != "collect-request" } ?: "a payment app"
        val newPayee = if (r.reasonCodes().contains(com.kavach.core.risk.RuleCode.NEW_PAYEE)) " a new payee" else ""
        val call = if (s.callLive) "is on a ${mins}-min call with an unknown number" else "was on a call with an unknown number"
        val claim = who?.let { " claiming to be $it" } ?: ""
        return "Kavach alert: ${profile.userName} $call$claim and has opened $app$amount${if (newPayee.isNotEmpty()) " to$newPayee" else ""}. " +
            "Please call them now. Reply HOLD to extend the pause by 10 minutes."
    }

    fun isHold(text: String): Boolean = HOLD.containsMatchIn(text)

    /** Only registered guardians can extend a pause: a scammer must not be able to HOLD or spoof one. */
    fun isFromGuardian(fromNumber: String, profile: UserProfile): Boolean {
        val n = Extractors.normalizeNumber(fromNumber)
        return n.isNotEmpty() && profile.guardians.any { Extractors.normalizeNumber(it.number) == n }
    }

    private fun formatRupees(v: Double): String {
        val n = v.toLong()
        val s = n.toString()
        if (s.length <= 3) return s
        // Indian digit grouping: 95,000 / 1,50,000
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$rest,$last3"
    }
}
