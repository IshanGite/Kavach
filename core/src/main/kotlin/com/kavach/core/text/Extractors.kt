package com.kavach.core.text

import java.text.Normalizer

/**
 * Deterministic text parsing. Regex handles OTPs, amounts, UPI IDs and links; the LLM handles meaning.
 * Nothing here can be talked out of its answer by a malicious transcript.
 */
object Extractors {
    /** PSP handles we recognise when a UPI ID is spoken aloud ("sharma dot verify at oksbi"). */
    val UPI_HANDLES = setOf(
        "oksbi", "okhdfcbank", "okicici", "okaxis", "ybl", "ibl", "axl", "paytm", "upi", "sbi", "apl",
        "icici", "hdfcbank", "axisbank", "pnb", "boi", "cnrb", "ybl", "fbl", "kotak", "idfcbank",
        "yesbank", "indus", "unionbank", "barodampay", "freecharge", "airtel", "jio", "postbank", "okbizaxis",
    )

    private val EMAIL_DOMAINS = setOf("gmail", "yahoo", "outlook", "hotmail", "rediffmail", "proton", "icloud", "live")

    private val UPI_WRITTEN = Regex("""([a-z0-9][a-z0-9._\-]{1,60})@([a-z][a-z0-9]{1,30})""")
    private val SPOKEN_AT = Regex("""\s+(?:at the rate of|at the rate|at rate|at)\s+""")
    private val SPOKEN_DOT = Regex("""\s+(?:dot|point)\s+""")
    private val SPOKEN_DASH = Regex("""\s+(?:dash|hyphen)\s+""")
    private val ACCOUNT_RUN = Regex("""\d(?:[\d\s-]{7,24}\d)""")
    private val AMOUNT = Regex("""(?:₹|rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val AMOUNT_SUFFIX = Regex("""([0-9][0-9,]*(?:\.[0-9]+)?)\s*(?:rupees|rupaye|rupay)""", RegexOption.IGNORE_CASE)
    private val OTP_CODE = Regex("""(?<![\d₹])(\d{4,8})(?!\d)""")
    private val OTP_WORDS = Regex("""\b(otp|one[- ]time (?:password|pin)|verification code|do not share|ओटीपी)\b""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""https?://[^\s]+|\b[a-z0-9-]+\.(?:xyz|top|club|online|site|click|link|info)/[^\s]*""", RegexOption.IGNORE_CASE)

    /** Last 10 digits, so +91 98765 43210, 098765 43210 and 9876543210 all compare equal. */
    fun normalizeNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun isInternationalPrefix(raw: String): Boolean {
        val t = raw.trim()
        return (t.startsWith("+") && !t.startsWith("+91")) || (t.startsWith("00") && !t.startsWith("0091"))
    }

    /** Lowercase, strip accents and surrounding noise. Used as the identity key for payees. */
    fun normalizePayee(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().lowercase().replace(Regex("""\s+"""), " ")

    fun spokenToWritten(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        t = SPOKEN_DOT.replace(t, ".")
        t = SPOKEN_DASH.replace(t, "-")
        // "name at oksbi" -> "name@oksbi", only when followed by a known PSP handle (avoids "at home").
        val handles = UPI_HANDLES.joinToString("|")
        t = Regex("""([a-z0-9._\-]+)${SPOKEN_AT.pattern}($handles)\b""").replace(t) { "${it.groupValues[1]}@${it.groupValues[2]}" }
        return t
    }

    /** UPI IDs written ("a.b@oksbi") or spoken ("a dot b at the rate of oksbi"). Emails are ignored. */
    fun extractUpiIds(text: String): List<String> {
        val t = spokenToWritten(text)
        return UPI_WRITTEN.findAll(t)
            .map { it.value }
            .filter { id ->
                val handle = id.substringAfter('@')
                handle in UPI_HANDLES || handle !in EMAIL_DOMAINS && !handle.contains('.')
            }
            .distinct()
            .toList()
    }

    /** 9 to 18 digit runs (spoken digit groups allowed), the shape of a bank account number. */
    fun extractAccountNumbers(text: String): List<String> =
        ACCOUNT_RUN.findAll(text)
            .map { it.value.filter(Char::isDigit) }
            .filter { it.length in 9..18 }
            .distinct()
            .toList()

    fun extractAmountRupees(text: String): Double? {
        val m = AMOUNT.find(text) ?: AMOUNT_SUFFIX.find(text) ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    fun looksLikeOtp(text: String): Boolean = OTP_WORDS.containsMatchIn(text) && OTP_CODE.containsMatchIn(text)

    fun containsSuspiciousLink(text: String): Boolean = URL.containsMatchIn(text)

    /**
     * Does the payee shown on the pay screen match something that was spoken on the call?
     * A near-certain coercion link: the scammer dictated the destination.
     */
    /**
     * Only a UPI ID or a bank account number is strong evidence that a payee was dictated. A business or person's NAME ("Sunrise Clinic")
     * is routinely said aloud on perfectly legitimate calls, so it must never trigger the "payee came from the call" override.
     */
    fun isPayeeIdentifier(raw: String): Boolean {
        val t = normalizePayee(raw)
        if (t.contains('@')) return UPI_WRITTEN.matches(t)
        val digits = t.filter { it.isDigit() }
        return digits.length in 9..18 && t.all { it.isDigit() || it.isWhitespace() || it == '-' }
    }

    fun payeeMatchesSpoken(payeeOnScreen: String?, spoken: Collection<String>): Boolean {
        if (payeeOnScreen.isNullOrBlank() || spoken.isEmpty()) return false
        val screen = normalizePayee(payeeOnScreen)
        val screenDigits = screen.filter(Char::isDigit)
        return spoken.filter(::isPayeeIdentifier).any { s ->
            val sp = normalizePayee(s)
            when {
                sp.isEmpty() -> false
                sp == screen -> true
                sp.contains('@') -> screen.contains(sp)
                sp.all(Char::isDigit) -> sp.length >= 9 && screenDigits.contains(sp)
                else -> false
            }
        }
    }
}
