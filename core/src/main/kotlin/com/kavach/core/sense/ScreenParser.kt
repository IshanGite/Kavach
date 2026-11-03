package com.kavach.core.sense

import com.kavach.core.model.PayStage
import com.kavach.core.text.Extractors

data class ParsedScreen(val stage: PayStage, val amountRupees: Double?, val payee: String?)

/**
 * Reads the visible text of a UPI app's current screen (collected from the accessibility node tree) into a payment stage,
 * amount and payee. Pure function, so it is tested against recorded screen dumps and is easy to retune per app on the phone:
 * capture a dump with the Debug screen, add it to ScreenParserTest, adjust here.
 */
object ScreenParser {
    private val RUPEE_NODE = Regex("""^\s*(?:₹|rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*$""", RegexOption.IGNORE_CASE)
    private val PAYEE_LABELS = listOf("paying", "pay to", "paying to", "sending to", "send to", "to:", "to ", "payee", "banking name", "भुगतान प्राप्तकर्ता")
    private val NOT_PAYEE = setOf("pay", "to", "from", "upi", "bank", "send", "paying", "amount", "add note", "pay now", "proceed")

    fun parse(texts: List<String>, pinHints: List<String>): ParsedScreen {
        val clean = texts.map { it.trim() }.filter { it.isNotEmpty() }.take(300)
        val joined = clean.joinToString("\n").lowercase()

        val pin = pinHints.any { it.isNotBlank() && joined.contains(it.lowercase()) }

        // Prefer a node that is exactly an amount ("₹50,000"); skip anything near the word "balance".
        val amountNode = clean.withIndex().firstNotNullOfOrNull { (i, t) ->
            val m = RUPEE_NODE.find(t) ?: return@firstNotNullOfOrNull null
            val ctx = clean.subList(maxOf(0, i - 1), minOf(clean.size, i + 2)).joinToString(" ").lowercase()
            if ("balance" in ctx) null else m.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        // Fallback: an amount embedded in a sentence ("Pay ₹500 to ..."). Exact amount nodes were already judged above.
        val amount = amountNode ?: clean.firstNotNullOfOrNull { t ->
            if (RUPEE_NODE.matches(t) || "balance" in t.lowercase()) null else Extractors.extractAmountRupees(t)
        }

        val upi = clean.firstNotNullOfOrNull { Extractors.extractUpiIds(it).firstOrNull() }
        val payee = upi ?: labelledPayee(clean)

        val stage = when {
            pin -> PayStage.PIN_SCREEN
            amount != null -> PayStage.AMOUNT_ENTERED
            else -> PayStage.OPENED
        }
        return ParsedScreen(stage, amount, payee)
    }

    private fun labelledPayee(texts: List<String>): String? {
        for ((i, t) in texts.withIndex()) {
            val lower = t.lowercase()
            val label = PAYEE_LABELS.firstOrNull { lower.startsWith(it) } ?: continue
            val inline = t.substring(label.length).trim().trimStart(':', '-').trim()
            val candidate = inline.ifEmpty { texts.getOrNull(i + 1)?.trim().orEmpty() }
            if (candidate.length in 2..60 && candidate.lowercase() !in NOT_PAYEE && !RUPEE_NODE.matches(candidate)) return candidate
        }
        return null
    }
}
