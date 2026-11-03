package com.kavach.app.ui

import com.kavach.core.intervention.Lang
import com.kavach.core.risk.RuleCode

/**
 * Plain-language names for the engine's three scores (C / L / R), English and Hindi.
 * All user-facing wording for them lives here; the raw numbers stay visible but secondary.
 */
object RiskLabels {
    enum class Bar { SITUATION, SAYING, RISK }

    private fun t(lang: Lang, en: String, hi: String) = if (lang == Lang.HI) hi else en

    fun name(bar: Bar, lang: Lang): String = when (bar) {
        Bar.SITUATION -> t(lang, "Situation", "हालात")
        Bar.SAYING -> t(lang, "What they're saying", "वे क्या कह रहे हैं")
        Bar.RISK -> t(lang, "Scam risk", "ठगी का ख़तरा")
    }

    /** Safe / Watching / Caution / Danger for tier levels 0..3. */
    fun tierWord(level: Int, lang: Lang): String = when (level.coerceIn(0, 3)) {
        0 -> t(lang, "Safe", "सुरक्षित")
        1 -> t(lang, "Watching", "निगरानी")
        2 -> t(lang, "Caution", "सावधान")
        else -> t(lang, "Danger", "ख़तरा")
    }

    /** Name with the raw number kept secondary, e.g. "Situation 25". */
    fun withNumber(bar: Bar, value: Int?, lang: Lang): String = "${name(bar, lang)} ${value ?: "–"}"

    private fun phrase(code: RuleCode, lang: Lang): String = when (code) {
        RuleCode.UNKNOWN_LIVE_CALL, RuleCode.UNKNOWN_VOIP_OR_VIDEO -> t(lang, "unknown caller", "अनजान कॉलर")
        RuleCode.LONG_CALL -> t(lang, "long call", "लंबी कॉल")
        RuleCode.PAYMENT_APP_DURING_CALL -> t(lang, "payment app open", "पेमेंट ऐप खुला")
        RuleCode.NEW_PAYEE, RuleCode.REPEATED_NEW_PAYEES -> t(lang, "new payee", "नया पाने वाला")
        RuleCode.AMOUNT_ABOVE_USUAL, RuleCode.ESCALATING_NEW_PAYEE_AMOUNTS -> t(lang, "large amount", "बड़ी रकम")
        RuleCode.ODD_HOUR -> t(lang, "odd hour", "अजीब समय")
        RuleCode.REMOTE_ACCESS_ACTIVE -> t(lang, "remote-access app", "रिमोट-एक्सेस ऐप")
        RuleCode.OTP_OR_COLLECT_DURING_CALL -> t(lang, "OTP / money request", "OTP / पैसे की माँग")
        RuleCode.SYNTHETIC_VOICE -> t(lang, "synthetic voice", "नकली आवाज़")
        RuleCode.NUMBER_ON_SCAM_LIST -> t(lang, "known scam number", "जाना-माना ठग नंबर")
        RuleCode.RELATED_SMS_FLAGGED -> t(lang, "scam SMS", "ठगी वाला SMS")
        RuleCode.INSTITUTION_FROM_PERSONAL_MOBILE -> t(lang, "official claim from a mobile", "मोबाइल से सरकारी/बैंक दावा")
    }

    /**
     * One short helper line under the bar. [codes] are the engine's rule hits (strongest first) when known.
     * Without them a generic line is used.
     */
    fun helper(bar: Bar, lang: Lang, codes: List<RuleCode> = emptyList(), languageScore: Int? = null, tierLevel: Int = 0): String =
        when (bar) {
            Bar.SITUATION -> {
                val parts = codes.map { phrase(it, lang) }.distinct().take(3)
                if (parts.isEmpty()) t(lang, "Unknown caller, payment app, new payee, large amount", "अनजान कॉलर, पेमेंट ऐप, नया पाने वाला, बड़ी रकम")
                else parts.joinToString(" + ").replaceFirstChar { it.uppercase() }
            }
            Bar.SAYING -> when {
                languageScore == null -> t(lang, "Threats, urgency, secrecy, money requests", "धमकी, जल्दबाज़ी, राज़दारी, पैसे की माँग")
                languageScore >= 50 -> t(lang, "Pressure or threats heard", "दबाव या धमकी सुनी गई")
                languageScore > 0 -> t(lang, "Some pressure words heard", "कुछ दबाव वाले शब्द सुने गए")
                else -> t(lang, "Nothing worrying heard", "चिंता वाली कोई बात नहीं सुनी")
            }
            Bar.RISK -> t(lang, "Overall: ${tierWord(tierLevel, lang)}", "कुल मिलाकर: ${tierWord(tierLevel, lang)}")
        }

    /** Compact one-line form for the floating widget: "Caution   Situation 25   Saying 40   Scam risk 60". */
    fun compact(lang: Lang, tierLevel: Int, context: Int, language: Int?, risk: Int): String =
        "${tierWord(tierLevel, lang)}   ${withNumber(Bar.SITUATION, context, lang)}   " +
            "${t(lang, "Saying", "बातचीत")} ${language ?: "–"}   ${withNumber(Bar.RISK, risk, lang)}"
}
