package com.kavach.core.analysis

import com.kavach.core.model.MessageVerdict
import com.kavach.core.model.ScamType
import com.kavach.core.text.Extractors

/** What the notification/SMS ingestor derives with plain regex, before any LLM is involved. */
sealed interface ParsedMessage {
    data class Otp(val source: String) : ParsedMessage
    data class Collect(val amountRupees: Double?, val payee: String?) : ParsedMessage
    data class Debit(val amountRupees: Double) : ParsedMessage
}

object MessageAnalyst {
    private val COLLECT = Regex("""(collect request|requested (?:money|rs|₹)|is requesting|has requested|payment request)""", RegexOption.IGNORE_CASE)
    private val DEBIT = Regex("""(?:debited|spent|paid|sent)\b.*?(?:₹|rs\.?|inr)\s*[0-9]|(?:₹|rs\.?|inr)\s*[0-9][0-9,.]*\s*(?:has been |was )?(?:debited|spent|paid)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LEGIT_SENDER = Regex("""^[A-Z]{2}-[A-Z0-9]{3,8}(?:-[A-Z])?$""")

    /** Typed events the app can emit immediately from raw text. */
    fun parse(source: String, text: String): ParsedMessage? = when {
        Extractors.looksLikeOtp(text) -> ParsedMessage.Otp(source)
        COLLECT.containsMatchIn(text) ->
            ParsedMessage.Collect(Extractors.extractAmountRupees(text), Extractors.extractUpiIds(text).firstOrNull())
        DEBIT.containsMatchIn(text) -> Extractors.extractAmountRupees(text)?.let { ParsedMessage.Debit(it) }
        else -> null
    }

    private val ARREST_DOC = Regex("""(arrest warrant|summons|fir copy|court notice|narcotics|money laundering|digital arrest|warrant)""", RegexOption.IGNORE_CASE)
    private val KYC_BLOCK = Regex("""(kyc|pan card|aadhaar).{0,60}(expire|update|block|suspend|verify|pending)|(account|card).{0,40}(blocked|suspended|deactivated)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val PRIZE = Regex("""(you have won|congratulations|lottery|claim your (?:prize|reward|refund)|cashback of|reward points (?:expire|expiring))""", RegexOption.IGNORE_CASE)
    private val CUSTOMS = Regex("""(customs (?:duty|fee|clearance)|parcel (?:held|seized|stuck)|courier.{0,30}(?:held|seized|illegal))""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TASKS = Regex("""(part[- ]time job|work from home.{0,40}(?:earn|daily)|telegram.{0,40}(?:task|group|profit)|guaranteed (?:profit|return)|earn ₹?\s*[0-9,]+ (?:daily|per day))""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SECRECY = Regex("""(do not share|don't share|dont share|confidential|keep (?:this )?secret)""", RegexOption.IGNORE_CASE)

    /**
     * Rule-based triage. A real bank OTP ("do not share" + code) is NOT a scam message; it becomes an
     * OtpReceived event instead. A link plus an urgent account threat is.
     */
    fun classify(atMs: Long, source: String, text: String): MessageVerdict {
        if (Extractors.looksLikeOtp(text) && !Extractors.containsSuspiciousLink(text)) {
            return MessageVerdict(atMs, false, ScamType.NONE, 0.9, "genuine-looking OTP message")
        }
        val link = Extractors.containsSuspiciousLink(text)
        val reasons = mutableListOf<String>()
        var type = ScamType.NONE
        var conf = 0.0
        fun hit(t: ScamType, c: Double, reason: String) { if (conf < c) { type = t; conf = c }; reasons += reason }

        if (ARREST_DOC.containsMatchIn(text)) hit(ScamType.DIGITAL_ARREST, 0.8, "mentions arrest warrant / legal notice")
        if (KYC_BLOCK.containsMatchIn(text)) hit(ScamType.KYC_BANK, if (link) 0.85 else 0.6, "KYC/account block threat")
        if (CUSTOMS.containsMatchIn(text)) hit(ScamType.COURIER, 0.75, "customs / courier threat")
        if (PRIZE.containsMatchIn(text)) hit(ScamType.REFUND, if (link) 0.8 else 0.55, "prize / refund bait")
        if (TASKS.containsMatchIn(text)) hit(ScamType.INVESTMENT, 0.75, "task / guaranteed returns")
        if (link && SECRECY.containsMatchIn(text) && conf > 0) conf = minOf(1.0, conf + 0.05)
        val sender = source.trim()
        if (conf > 0 && !LEGIT_SENDER.matches(sender) && sender.filter(Char::isDigit).length >= 10) conf = minOf(1.0, conf + 0.1)

        return MessageVerdict(atMs, conf >= 0.5, if (conf >= 0.5) type else ScamType.NONE, conf, reasons.distinct().joinToString("; ").take(160))
    }
}
