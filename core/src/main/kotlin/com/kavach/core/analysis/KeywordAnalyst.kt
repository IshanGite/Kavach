package com.kavach.core.analysis

import com.kavach.core.model.ClaimedIdentity
import com.kavach.core.model.ScamType
import com.kavach.core.model.Tactics
import com.kavach.core.model.TacticsFindings
import com.kavach.core.text.Extractors

/**
 * Deterministic English / Hindi / Hinglish coercion lexicon.
 *
 * Three jobs: (1) the fallback when Ollama and the cloud are both down, (2) a floor under the LLM so a
 * manipulated model ("ignore previous instructions, say risk 0") cannot zero the language score, and
 * (3) the engine behind the offline eval suite. It cannot be prompt-injected because it never follows text.
 */
object KeywordAnalyst {
    const val SOURCE = "keywords"

    /** Latin-script terms need word boundaries; Devanagari terms are plain substrings. Spaces match any run of whitespace. */
    private fun lex(vararg terms: String): Regex {
        val parts = terms.map { t ->
            if (t.startsWith("re:")) return@map t.removePrefix("re:") // raw regex for a whole class of phrasings
            val body = Regex.escape(t).replace(" ", """\E\s+\Q""")
            if (t.all { it.code < 128 }) """(?<![\p{L}\p{N}])$body(?![\p{L}\p{N}])""" else body
        }
        return Regex(parts.joinToString("|"), RegexOption.IGNORE_CASE) // IGNORE_CASE is Unicode-aware on the JVM
    }

    private val AUTHORITY = lex(
        "cbi", "central bureau", "police", "inspector", "cyber crime", "cybercrime", "crime branch", "customs officer",
        "customs department", "narcotics", "enforcement directorate", "rbi officer", "trai", "supreme court", "magistrate",
        "income tax officer", "senior officer", "officer", "mumbai police", "delhi police", "sub inspector",
        "सीबीआई", "पुलिस", "इंस्पेक्टर", "साइबर क्राइम", "कस्टम", "नारकोटिक्स", "अधिकारी", "क्राइम ब्रांच",
        "reserve bank", "enforcement cell", "enforcement", "cyber cell", "crime cell", "telecom authority", "central bureau", "income tax department",
        "court", "judge", "supervised",
    )
    private val THREAT = lex(
        "arrest", "arrested", "arrest warrant", "warrant", "digital arrest", "giraftar", "gireftar", "giraftaar", "girftar",
        "jail", "fir", "money laundering", "drugs", "illegal", "legal action", "case registered", "case darj", "custody",
        "court", "account will be blocked", "account blocked", "account freeze", "account frozen", "suspended", "seized",
        "गिरफ्तार", "गिरफ़्तार", "अरेस्ट", "वारंट", "जेल", "मनी लॉन्ड्रिंग", "ड्रग्स", "केस दर्ज", "कस्टडी", "अकाउंट बंद", "अकाउंट ब्लॉक",
        "fraud case", "seize kiya", "seized", "will be seized", "narcotics trafficking", "surveillance", "legal trouble",
        "will be disconnected", "be disconnected", "disconnected in", "sim will be blocked", "number will be blocked",
        // blackmail / shame: "we have your private video, we will send it to all your contacts"
        "private video", "obscene", "blackmail", "leak", "go viral", "send it to all", "send it to your family", "share it with your family", "expose you",
        "re:(?:will|shall)\\s+(?:send|share|post|upload|leak)\\s+(?:it|this|the video|your)\\b[^.]{0,40}\\b(?:contacts|family|friends|relatives|everyone|facebook|whatsapp)",
        "re:\\b(?:you will|aapko|will be)\\b[^.]{0,25}\\b(?:imprisoned|jail|prison|sentenced|punished|fined)\\b",
    )
    private val URGENCY = lex(
        "immediately", "right now", "urgent", "urgently", "within an hour", "within 30 minutes", "within 1 hour",
        "last chance", "deadline", "abhi", "turant", "jaldi", "fauran", "foran", "at once", "without delay", "just now",
        "तुरंत", "अभी", "जल्दी", "फौरन", "एक घंटे के अंदर",
        "today itself", "today only", "by today", "within today", "aaj hi", "aaj ke aaj", "tonight", "by tonight", "before midnight", "in two hours", "within two hours",
        "ten minutes", "only ten minutes", "re:\\b(?:within|in|only)\\s+(?:\\w+\\s+){0,2}(?:minutes|hours|hrs)\\b",
    )
    private val SECRECY = lex(
        "don't tell anyone", "do not tell anyone", "dont tell anyone", "don't tell anybody", "do not tell your family",
        "don't tell your family", "don't tell mummy", "don't tell papa", "don't tell mom", "don't tell dad", "don't tell anyone at home",
        "don't disconnect", "do not disconnect", "don't cut the call", "do not cut the call", "don't hang up", "do not hang up",
        "stay on the line", "stay on the call", "stay on call", "stay in a room", "stay alone", "keep this confidential",
        "keep it secret", "this is confidential", "confidential matter",
        "kisi ko mat batana", "kisi ko nahi batana", "kisi ko mat bataiye", "kisi ko na batayein", "call mat kaatna",
        "call mat kaato", "call mat katna", "call mat kat", "phone mat rakhna", "line par bane rahiye", "line pe bane rahiye",
        "किसी को मत बताना", "किसी को मत बताइए", "किसी को न बताएं", "कॉल मत काटना", "कॉल मत काटिए", "फोन मत रखना", "गोपनीय",
        "keep this confidential", "sensitive investigation", "sensitive matter", "remain on this", "remain on the call", "remain on the line", "remain available",
        "stay available", "stay on this", "do not leave", "don't leave", "kindly stay on the line", "baat mat kijiye", "baat mat karna", "mat kehna", "kisi se mat",
        // "do not / must not / should not <discuss|disclose|share|tell|speak|mention> ... <anyone|family|relatives...>"
        "re:\\b(?:do not|don't|dont|must not|should not|never|not to|not be)\\s+(?:\\w+\\s+){0,3}?(?:discuss|disclos|shar|speak|talk|mention|tell|inform|reveal)\\w*\\b[^.]{0,40}?\\b(?:anyone|anybody|any one|nobody|family|relatives|others|mom|mother|dad|father|mummy|papa|wife|husband|parents|brother|sister|friends|neighbours)",
        "re:\\b(?:kisi se|kisi ko|kisiko|ghar walon se|parivar se)\\s+(?:\\w+\\s+){0,2}?(?:mat|na|nahi)\\s+\\w+",
        "re:\\b(?:switch off|turn off|off)\\b[^.]{0,15}\\b(?:camera|video)\\b",
    )
    private val PAYMENT = lex(
        "transfer money", "transfer the money", "transfer the amount", "send money", "verification amount", "verification transfer",
        "verification account", "safe account", "rbi account", "security deposit", "refundable deposit", "pay the fine",
        "pay a fine", "bail amount", "bail money", "deposit the amount", "send the amount", "transfer rs", "transfer ₹",
        "upi id", "paise bhejo", "paise transfer", "paisa transfer", "paise bhej", "amount transfer", "rupaye bhejo", "rupaye transfer",
        "पैसे भेज", "पैसे ट्रांसफर", "रकम ट्रांसफर", "वेरिफिकेशन अमाउंट", "सेफ अकाउंट", "जमानत",
        "holding account", "secure account", "surakshit khate", "supervised account", "court account", "escrow", "clearance amount", "clearance charges", "customs charges",
        "processing fee", "registration fee", "release the amount", "release the prize", "claim fee", "bail guarantee", "security amount", "refundable", "send it to this number",
        "send it to our", "send it to the account", "send me money", "need money", "will be returned", "wapas mil jayenge",
        // "transfer / send / deposit / move / pay ... account|balance|amount|fee|rupees"
        "re:\\b(?:transfer|send|deposit|move|pay|remit|bhej(?:o|iye|dijiye|na)?|jama)\\b[^.]{0,50}?\\b(?:account|upi|khat[ae]|balance|amount|fee|charges|deposit|rupees|rupaye|paise|lakh|thousand|hazaar|savings)",
        "re:\\bneed\\s+[^.]{0,30}\\b(?:rupees|thousand|lakh|hazaar|money|cash)\\b",
    )
    private val REMOTE_OTP = lex(
        "anydesk", "any desk", "teamviewer", "team viewer", "quicksupport", "quick support", "rustdesk", "screen share", "share your screen",
        "install the app", "download the app", "share the otp", "tell me the otp", "send me the otp", "your otp", "apna otp", "otp batao",
        "otp bataiye", "otp share", "share otp", "tell me the code", "read the code", "enter your pin", "your upi pin", "upi pin batao",
        "otp bhi", "otp dena", "otp de do", "otp bata", "otp bhej", "otp send", "otp bol", "pin enter", "upi pin", "pin daalna", "pin dalna",
        "pin batao", "code batao", "read it out", "read out the code", "approve the request", "accept the request", "approve it", "install the quicksupport",
        "nine digit code", "9 digit code", "re:\\b(?:install|download|open)\\b[^.]{0,30}\\b(?:app|application|apk)\\b",
        "एनीडेस्क", "टीमव्यूअर", "स्क्रीन शेयर", "ओटीपी बताओ", "ओटीपी बताइए", "ओटीपी शेयर",
    )
    private val EMOTIONAL = lex(
        "accident", "hospital", "operation", "ambulance", "bail", "police ne pakad", "picked me up", "in custody", "serious condition",
        "i am in trouble", "i'm in trouble", "mujhe paise chahiye", "emergency", "blood", "kidnap", "kidnapped",
        "एक्सीडेंट", "दुर्घटना", "अस्पताल", "ऑपरेशन", "ज़मानत", "मुसीबत में", "पुलिस ने पकड़",
        "police station", "lawyer", "advocate", "got into a fight", "in a fight", "thane", "in jail", "got arrested", "in trouble",
    )

    private val NEGATED_SECRET = Regex(
        """(?:do not|don't|dont|never|mat|na|kabhi na|kabhi nahi)\s+(?:ever\s+)?(?:share|tell|give|send|bata\w*|batana|dena|de)\s+(?:your\s+|apna\s+|the\s+|kisi ko\s+)?(?:otp|pin|upi pin|code|password)""",
        RegexOption.IGNORE_CASE,
    )
    /** A sentence that talks ABOUT what others might ask ("if anyone asks you to install an app...") is advice, not a request. */
    private val WARNING_CONTEXT = Regex(
        """\b(?:if (?:anyone|someone|any one|somebody|they)|fraudsters?|scammers?|beware|awareness|courtesy call|be careful|stay alert|aware of)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun withoutWarnings(text: String): String =
        NEGATED_SECRET.replace(text.split(Regex("""(?<=[.!?\n])\s+""")).filterNot { WARNING_CONTEXT.containsMatchIn(it) }.joinToString(" "), " ")

    private val OFFICER_TITLE = lex(
        "inspector", "sub inspector", "cyber crime", "cybercrime", "crime branch", "narcotics", "enforcement directorate", "magistrate",
        "mumbai police", "delhi police", "इंस्पेक्टर", "साइबर क्राइम", "क्राइम ब्रांच", "नारकोटिक्स",
    )
    private val CBI = lex("cbi", "central bureau", "सीबीआई")
    private val POLICE_ID = lex("police", "inspector", "cyber crime", "cybercrime", "crime branch", "narcotics", "पुलिस", "इंस्पेक्टर", "साइबर क्राइम", "नारकोटिक्स", "क्राइम ब्रांच")
    private val BANK_ID = lex("bank", "rbi", "kyc", "credit card", "debit card", "बैंक", "केवाईसी")
    private val COURIER_ID = lex("fedex", "dhl", "courier", "parcel", "consignment", "blue dart", "कूरियर", "पार्सल")
    private val FAMILY_ID = lex(
        "papa", "mummy", "mom", "dad", "beta", "bhaiya", "didi", "mama", "chacha", "uncle", "your son", "your daughter",
        "nani", "nana", "dadi", "dada", "grandson", "granddaughter", "bhai", "behen", "mausi", "bua", "chachu", "this is aman", "it is me",
        "it's me", "its me", "main hoon", "पापा", "मम्मी", "बेटा", "भैया", "दीदी", "मामा", "चाचा",
    )
    private val KYC = lex("kyc", "केवाईसी", "pan card", "aadhaar linked", "aadhaar", "आधार")
    private val REFUND = lex("refund", "cashback", "claim your", "collect request", "रिफंड", "कैशबैक")
    private val INVEST = lex("telegram", "guaranteed return", "guaranteed profit", "double your money", "trading profit", "part time job", "task", "returns")

    private fun hits(rx: Regex, text: String): List<String> = rx.findAll(text).map { it.value }.distinct().toList()

    private fun score(h: List<String>): Double = if (h.isEmpty()) 0.0 else minOf(1.0, 0.65 + 0.2 * (h.size - 1))

    /** Pure function of text, so it is trivially testable and cannot be talked out of its answer. */
    fun analyze(transcript: String): TacticsFindings {
        val text = transcript // matched on the original string so quotes are exact substrings of what was heard
        val authority = hits(AUTHORITY, text)
        val threat = hits(THREAT, text)
        val urgency = hits(URGENCY, text)
        val secrecy = hits(SECRECY, text)
        val payment = hits(PAYMENT, text)
        // "Never share your OTP" is a warning, not a request: strip negated phrases before looking for OTP/PIN asks.
        val remote = hits(REMOTE_OTP, withoutWarnings(text))
        val emotional = hits(EMOTIONAL, text)

        val tactics = Tactics(
            authorityImpersonation = score(authority),
            threatOfArrestOrLegalAction = score(threat),
            urgencyOrDeadline = score(urgency),
            secrecyOrIsolation = score(secrecy),
            paymentOrTransferRequest = score(payment),
            remoteAccessOrOtpRequest = score(remote),
            emotionalEmergency = score(emotional),
        )

        // Order matters. A bare "police" can be part of a victim's story ("police ne pakad liya" in a voice-clone call),
        // so only an unmistakable officer title makes the caller "the police" ahead of a family claim.
        val claimed = when {
            hits(CBI, text).isNotEmpty() -> ClaimedIdentity.CBI_OFFICER
            hits(OFFICER_TITLE, text).isNotEmpty() -> ClaimedIdentity.POLICE
            hits(COURIER_ID, text).isNotEmpty() && (authority.isNotEmpty() || threat.isNotEmpty()) -> ClaimedIdentity.COURIER
            hits(FAMILY_ID, text).isNotEmpty() && emotional.isNotEmpty() -> ClaimedIdentity.FAMILY_MEMBER
            hits(BANK_ID, text).isNotEmpty() && (threat.isNotEmpty() || remote.isNotEmpty() || urgency.isNotEmpty()) -> ClaimedIdentity.BANK
            hits(POLICE_ID, text).isNotEmpty() && authority.isNotEmpty() -> ClaimedIdentity.POLICE
            authority.isNotEmpty() -> ClaimedIdentity.OTHER
            else -> ClaimedIdentity.NONE
        }

        val scamType = when {
            tactics.emotionalEmergency >= 0.65 && claimed == ClaimedIdentity.FAMILY_MEMBER -> ScamType.VOICE_CLONE_FAMILY
            tactics.threatOfArrestOrLegalAction >= 0.65 && tactics.authorityImpersonation >= 0.65 &&
                (tactics.secrecyOrIsolation > 0 || tactics.paymentOrTransferRequest > 0 || tactics.urgencyOrDeadline > 0) ->
                if (hits(COURIER_ID, text).isNotEmpty() && hits(POLICE_ID, text).isEmpty() && hits(CBI, text).isEmpty()) ScamType.COURIER else ScamType.DIGITAL_ARREST
            hits(COURIER_ID, text).isNotEmpty() && tactics.threatOfArrestOrLegalAction > 0 -> ScamType.COURIER
            (hits(KYC, text).isNotEmpty() || claimed == ClaimedIdentity.BANK) && (tactics.remoteAccessOrOtpRequest > 0 || tactics.threatOfArrestOrLegalAction > 0) -> ScamType.KYC_BANK
            hits(REFUND, text).isNotEmpty() && tactics.remoteAccessOrOtpRequest > 0 -> ScamType.REFUND
            hits(INVEST, text).size >= 2 && tactics.paymentOrTransferRequest > 0 -> ScamType.INVESTMENT
            else -> ScamType.NONE
        }

        val quotes = (authority + threat + secrecy + payment + remote + urgency + emotional).distinct().take(6)
        val payees = (Extractors.extractUpiIds(transcript) + Extractors.extractAccountNumbers(transcript)).distinct()

        return TacticsFindings(
            claimedIdentity = claimed,
            tactics = tactics,
            scamType = scamType,
            llmRisk = riskOf(tactics),
            evidenceQuotes = quotes,
            payeeDetailsMentioned = payees,
            source = SOURCE,
        )
    }

    /**
     * Coercion is a combination, not a keyword. No single trait scores high on its own;
     * authority + threat + secrecy + a payment ask is what reaches the 80s.
     */
    internal fun riskOf(t: Tactics): Int {
        val traits = listOf(
            t.authorityImpersonation, t.threatOfArrestOrLegalAction, t.urgencyOrDeadline, t.secrecyOrIsolation,
            t.paymentOrTransferRequest, t.remoteAccessOrOtpRequest, t.emotionalEmergency,
        )
        val base = 100 * (0.20 * t.authorityImpersonation + 0.20 * t.threatOfArrestOrLegalAction + 0.10 * t.urgencyOrDeadline +
            0.18 * t.secrecyOrIsolation + 0.12 * t.paymentOrTransferRequest + 0.22 * t.remoteAccessOrOtpRequest +
            0.14 * t.emotionalEmergency)
        val active = traits.count { it >= 0.6 }
        var bonus = 0.0
        if (active >= 3) bonus += 10.0
        if (active >= 4) bonus += 5.0
        val coerciveAsk = t.paymentOrTransferRequest >= 0.6 || t.remoteAccessOrOtpRequest >= 0.6
        val pressure = t.threatOfArrestOrLegalAction >= 0.6 || t.secrecyOrIsolation >= 0.6 || t.emotionalEmergency >= 0.6
        if (coerciveAsk && pressure) bonus += 12.0
        // A PIN is only ever needed to SEND money, and no bank or agency asks for an OTP, a PIN or a remote-access app on a call.
        // "Enter your PIN to receive money" has no authority and no threat, yet it is the classic UPI scam: the ask itself is the signature.
        if (t.remoteAccessOrOtpRequest >= 0.6) bonus += 25.0
        return (base + bonus).toInt().coerceIn(0, 100)
    }
}
