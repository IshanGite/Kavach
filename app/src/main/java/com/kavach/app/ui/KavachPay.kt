package com.kavach.app.ui

import com.kavach.core.model.CallEnded
import com.kavach.core.model.CallStarted
import com.kavach.core.model.KavachEvent
import com.kavach.core.model.PayStage
import com.kavach.core.model.PaymentDismissed
import com.kavach.core.model.PaymentIntent
import com.kavach.core.model.Speaker
import com.kavach.core.model.TranscriptChunk
import com.kavach.core.risk.Tier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Kavach Pay, a simulated UPI screen inside the Kavach app.
 *
 * Package semantics are [PACKAGE_NAME]: nothing here is a real payment app, and no money moves.
 * [paymentIntents] matches what [com.kavach.app.sense.KavachAccessibilityService] emits for a real
 * UPI app. [PayStage] has no separate payee stage; the payee rides on [PayStage.AMOUNT_ENTERED],
 * which is the screen the parser reports once both the payee and the amount are visible.
 * [PayStage.PIN_SCREEN] is a later event. The UI sends it only after a tier 2+ pause has been continued.
 *
 * The PIN itself is never a field on these events. The keypad keeps a length only.
 */
object KavachPay {
    const val PACKAGE_NAME = "com.kavach.app.pay"
    const val APP_NAME = "Kavach Pay"
    const val ROUTE = "pay"
    const val TITLE = "Kavach Pay"
    const val NAV_LABEL = "Kavach Pay (demo payment app)"
    const val DEMO_BANNER = "DEMO: no real money"
    const val SUCCESS_TEXT = "Payment simulated, no money moved"
    const val NEW_PAYEE = "new"

    /** Fictional caller used by the demo scripts. Not on the bundled scam-number list. */
    const val COERCED_CALLER = "+91 99887 76655"

    /**
     * Spoken the way [com.kavach.core.text.Extractors] turns "dot" / "at" into a UPI id, so the
     * live engine can link this payee to the call before any language model returns.
     */
    const val COERCED_TRANSCRIPT =
        "I am Inspector Sharma from Cyber Crime. You will be arrested. Do not tell anyone. Transfer money now to verify dot pay at oksbi."
    const val COERCED_UPI = "verify.pay@oksbi"

    val savedPayees: List<SavedPayee> = listOf(
        SavedPayee("mom", "Mom", "mom.sharma@oksbi"),
        SavedPayee("rahul", "Rahul", "rahul.son@okhdfcbank"),
        SavedPayee("landlord", "Landlord", "landlord.sharma@okhdfcbank"),
    )

    fun blankForm() = PayForm()

    fun rolePlayForm(role: RolePlay): PayForm = when (role) {
        RolePlay.NORMAL -> PayForm(selection = "rahul", amountText = "500", note = "dinner")
        RolePlay.COERCED -> PayForm(selection = NEW_PAYEE, newUpiId = COERCED_UPI, amountText = "50000", note = "verification")
    }

    /**
     * Live-engine events for a role-play button. Normal ends any demo call and archives the
     * session so a following small payment is not still sitting inside the scam. Coerced starts
     * an unknown call and injects one scam line. Neither event list touches the microphone.
     */
    fun contextEvents(role: RolePlay, atMs: Long): List<KavachEvent> {
        var t = atMs
        fun next() = t.also { t += 1 }
        val clear = listOf(PaymentDismissed(next()), CallEnded(next(), 1))
        return when (role) {
            RolePlay.NORMAL -> clear + listOf(
                CallStarted(next(), "+91 98200 11122", isInContacts = true, contactName = "Rahul"),
                CallEnded(next(), 1),
            )
            RolePlay.COERCED -> clear + listOf(
                CallStarted(next(), COERCED_CALLER, isInContacts = false),
                TranscriptChunk(next(), COERCED_TRANSCRIPT, speaker = Speaker.CALLER),
            )
        }
    }

    fun resolve(form: PayForm): ResolvedPay? {
        val amount = parseAmountRupees(form.amountText) ?: return null
        val note = form.note.trim()
        val saved = savedPayees.firstOrNull { it.id == form.selection }
        if (saved != null) return ResolvedPay(saved.label, saved.upiId, amount, note, saved = true)
        if (form.selection != NEW_PAYEE) return null
        val upi = normalizeUpi(form.newUpiId) ?: return null
        return ResolvedPay("New payee", upi, amount, note, saved = false)
    }

    fun invalidReason(form: PayForm): String {
        if (form.selection == NEW_PAYEE && normalizeUpi(form.newUpiId) == null) {
            return "Enter a UPI id for the new payee, like name@bank."
        }
        if (parseAmountRupees(form.amountText) == null) return "Enter an amount in rupees."
        return "Choose who to pay."
    }

    /** Opened, then amount and payee. No PIN stage. */
    fun paymentIntents(pay: ResolvedPay, atMs: Long): List<PaymentIntent> = listOf(
        PaymentIntent(atMs, APP_NAME, PayStage.OPENED),
        PaymentIntent(atMs + 1, APP_NAME, PayStage.AMOUNT_ENTERED, pay.amountRupees, pay.upiId),
    )

    fun pinIntent(pay: ResolvedPay, atMs: Long): PaymentIntent =
        PaymentIntent(atMs, APP_NAME, PayStage.PIN_SCREEN, pay.amountRupees, pay.upiId)

    /** Tier 2 and 3 use the standard pause overlay. The mock PIN waits until the user continues. */
    fun pinWaitsForPause(tierLevel: Int): Boolean = tierLevel >= Tier.PAUSE.level

    /** Any 4, 5, or 6 keypresses. The digits are not an argument, because they are never kept. */
    fun isAcceptablePinLength(length: Int): Boolean = length in 4..6

    fun parseAmountRupees(raw: String): Double? {
        var t = raw.trim().lowercase()
        if (t.startsWith("₹")) t = t.removePrefix("₹")
        t = t.trim()
        if (t.startsWith("rs.")) t = t.removePrefix("rs.").trim()
        else if (t.startsWith("rs")) t = t.removePrefix("rs").trim()
        t = t.replace(",", "").replace(" ", "")
        if (!t.matches(Regex("""\d+(\.\d{1,2})?"""))) return null
        val value = t.toDouble()
        if (value <= 0.0 || value > 10_000_000.0) return null
        return value
    }

    fun normalizeUpi(raw: String): String? {
        val t = raw.trim()
        if (t.length !in 3..80 || t.any { it.isWhitespace() }) return null
        val at = t.indexOf('@')
        if (at <= 0 || at != t.lastIndexOf('@') || at == t.lastIndex) return null
        return t
    }

    fun formatRupees(amount: Double): String =
        if (amount % 1.0 == 0.0) "₹${amount.toLong()}"
        else "₹${String.format(Locale.US, "%.2f", amount)}"
}

enum class RolePlay { NORMAL, COERCED }

data class SavedPayee(val id: String, val label: String, val upiId: String)

data class PayForm(
    val selection: String = "mom",
    val newUpiId: String = "",
    val amountText: String = "",
    val note: String = "",
)

data class ResolvedPay(
    val label: String,
    val upiId: String,
    val amountRupees: Double,
    val note: String,
    val saved: Boolean,
)

data class SimulatedPayment(
    val label: String,
    val upiId: String,
    val amountRupees: Double,
    val note: String,
    val atMs: Long,
)

/** In-memory list for the screen. Completed payments are also recorded on the live baseline. */
object KavachPayLedger {
    private val _items = MutableStateFlow<List<SimulatedPayment>>(emptyList())
    val items: StateFlow<List<SimulatedPayment>> = _items.asStateFlow()

    fun add(payment: SimulatedPayment) {
        _items.update { (it + payment).takeLast(20) }
    }
}
