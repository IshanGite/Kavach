package com.kavach.app.ui

import com.kavach.app.engine.ScamNotice
import com.kavach.app.engine.WidgetSurface
import com.kavach.core.intervention.Lang

/**
 * Turns an engine snapshot plus what the user has already dismissed into the bubble or the card.
 * Pure: no Android, no clock of its own, and it never picks a tier. [WidgetSurface.tierLevel] is shown as-is.
 */
enum class WidgetCard { BUBBLE, LIVE, ALERT, HISTORY }

enum class WidgetTierBand {
    SAFE, WATCHING, CAUTION, DANGER;

    companion object {
        fun fromLevel(level: Int) = when (level.coerceIn(0, 3)) {
            0 -> SAFE
            1 -> WATCHING
            2 -> CAUTION
            else -> DANGER
        }
    }
}

data class HighlightSpan(val start: Int, val endExclusive: Int)

data class HighlightedLine(val text: String, val spans: List<HighlightSpan>)

data class DisplayedAlert(val id: String, val source: String, val why: String, val excerpt: String)

/** Remembered so a collapse or dismiss stays down until something new happens. */
data class Quiet(
    val callKey: String?,
    val alertId: String?,
    val tier: Int,
    val hardDismiss: Boolean,
)

data class WidgetUiLatch(
    val quiet: Quiet? = null,
    val dismissedAlertIds: Set<String> = emptySet(),
    val historyOpen: Boolean = false,
    val historyEpoch: String? = null,
)

data class WidgetView(
    val card: WidgetCard,
    val tier: WidgetTierBand,
    val tierLabel: String,
    val contextScore: Int,
    val languageScore: Int?,
    val risk: Int,
    val callerTitle: String?,
    val callerSubtitle: String?,
    val callElapsedSec: Long?,
    val languageLabel: String?,
    val transcript: List<HighlightedLine>,
    val listeningHint: String?,
    val reasons: List<String>,
    val alertTitle: String?,
    val alertSource: String?,
    val alertExcerpt: String?,
    val alertWhy: String?,
    val footnote: String?,
    val recentAlerts: List<DisplayedAlert>,
    val adviceTitle: String?,
    val advice: List<String>,
    val paymentRiskLabel: String,
    val transcriptTitle: String,
    val reasonsTitle: String,
    val whyTitle: String,
    val helplineLabel: String,
    val familyLabel: String?,
    val dismissLabel: String,
    val collapseLabel: String,
    val backLabel: String,
    val recentLabel: String,
    val alertCount: Int,
    val scoreLine: String = "",
) {
    /** There is no state in which the overlay eats taps that land outside the bubble or the card. */
    val consumesTouchesOutside: Boolean get() = false
}

object WidgetWindowPolicy {
    const val FLAG_NOT_FOCUSABLE = 0x00000008
    const val FLAG_NOT_TOUCHABLE = 0x00000010
    const val FLAG_NOT_TOUCH_MODAL = 0x00000020
    const val HELPLINE = "1930"

    /** The window receives touches on itself and nothing else. It is not focusable, so keys stay with the app underneath. */
    fun touchFlags(): Int = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL

    fun consumesTouchesOutside(): Boolean = false

    /**
     * Bubble or card size in dp. Never [android.view.ViewGroup.LayoutParams.MATCH_PARENT] (−1).
     * The controller also caps the card against the real screen so a short phone is not covered.
     */
    fun layoutSizeDp(card: WidgetCard): Pair<Int, Int> =
        if (card == WidgetCard.BUBBLE) 72 to 72 else 308 to 440
}

internal object WidgetCopy {
    fun t(lang: Lang, en: String, hi: String) = if (lang == Lang.HI) hi else en

    fun tier(lang: Lang, band: WidgetTierBand) = when (band) {
        WidgetTierBand.SAFE -> t(lang, "Safe", "सुरक्षित")
        WidgetTierBand.WATCHING -> t(lang, "Watching", "निगरानी")
        WidgetTierBand.CAUTION -> t(lang, "Caution", "सावधान")
        WidgetTierBand.DANGER -> t(lang, "Danger", "खतरा")
    }

    fun unknownCaller(lang: Lang) = t(lang, "Unknown caller", "अज्ञात कॉलर")
    fun callHelpline(lang: Lang) = t(lang, "Call 1930", "1930 पर कॉल")
    fun callFamily(lang: Lang, name: String) = t(lang, "Call $name", "$name को कॉल")
    fun dismiss(lang: Lang) = t(lang, "Dismiss", "बंद करें")
    fun collapse(lang: Lang) = t(lang, "Collapse", "छोटा करें")
    fun back(lang: Lang) = t(lang, "Back", "वापस")
    fun recent(lang: Lang) = t(lang, "Recent alerts", "हाल के अलर्ट")
    fun paymentRisk(lang: Lang) = t(lang, "Payment risk", "पेमेंट जोखिम")
    fun listening(lang: Lang) = t(lang, "Listening for speech", "आवाज़ का इंतज़ार है")
    fun transcriptTitle(lang: Lang) = t(lang, "What they're saying", "वे क्या कह रहे हैं")
    fun reasonsTitle(lang: Lang) = t(lang, "What Kavach noticed", "Kavach ने क्या देखा")
    fun whyTitle(lang: Lang) = t(lang, "Why this looks like a scam", "यह ठगी क्यों लगती है")
    fun adviceTitle(lang: Lang) = t(lang, "What to do", "क्या करें")
    fun alertTitle(lang: Lang) = t(lang, "Scam message", "ठगी जैसा संदेश")
    fun doNotClick(lang: Lang) = t(lang, "Do not click the link.", "लिंक पर क्लिक न करें।")
    fun doNotPay(lang: Lang) = t(lang, "Do not pay or share a PIN or OTP.", "पैसे न भेजें और PIN या OTP न बताएं।")
    fun callIfPaid(lang: Lang) = t(lang, "If money already moved, call 1930.", "पैसे निकल चुके हों तो 1930 पर कॉल करें।")
    fun advice(lang: Lang) = listOf(doNotClick(lang), doNotPay(lang), callIfPaid(lang))
    fun footnote(lang: Lang, why: String) = t(lang, "Message looks like a scam: $why", "संदेश ठगी जैसा है: $why")

    fun plainWhy(notice: ScamNotice, lang: Lang): String {
        val raw = notice.why.ifBlank { notice.typeName.lowercase().replace('_', ' ') }
        if (lang != Lang.HI) return raw
        return raw.split(';').joinToString("; ") { part -> HI_WHY[part.trim()] ?: part.trim() }.ifBlank { raw }
    }

    fun languageName(ui: Lang, raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase()?.removePrefix("<|")?.removeSuffix("|>") ?: return null
        if (cleaned.isEmpty() || cleaned == "auto" || cleaned == "und" || cleaned == "none") return null
        return when (cleaned.substringBefore('-').substringBefore('_')) {
            "en" -> t(ui, "English", "अंग्रेज़ी")
            "hi" -> t(ui, "Hindi", "हिन्दी")
            "mr" -> t(ui, "Marathi", "मराठी")
            else -> cleaned.substringBefore('-').substringBefore('_')
        }
    }

    private val HI_WHY = mapOf(
        "mentions arrest warrant / legal notice" to "गिरफ्तारी वारंट या कानूनी नोटिस का ज़िक्र",
        "KYC/account block threat" to "केवाईसी या अकाउंट बंद करने की धमकी",
        "customs / courier threat" to "कस्टम या कूरियर की धमकी",
        "prize / refund bait" to "इनाम या रिफंड का लालच",
        "task / guaranteed returns" to "टास्क या गारंटीड कमाई",
    )
}

object WidgetMapper {
    fun shouldShow(protectionOn: Boolean, widgetEnabled: Boolean): Boolean = protectionOn && widgetEnabled

    fun project(
        surface: WidgetSurface,
        latch: WidgetUiLatch = WidgetUiLatch(),
        nowMs: Long = surface.atMs,
        lang: Lang = Lang.EN,
        familyName: String? = null,
        fullScreenPauseShowing: Boolean = false,
    ): WidgetView {
        val sit = situation(surface, latch)
        val band = WidgetTierBand.fromLevel(surface.tierLevel)
        val caller = callerLines(surface, lang)
        val elapsed = if (surface.callLive && surface.callStartedAtMs != null) {
            (nowMs - surface.callStartedAtMs).coerceAtLeast(0) / 1000
        } else null
        val lines = surface.transcript.takeLast(3).filter { it.text.isNotBlank() }.map { line ->
            HighlightedLine(line.text, highlightSpans(line.text, surface.evidenceQuotes))
        }
        val recent = surface.scamNotices.map { n ->
            DisplayedAlert(n.id, n.source, WidgetCopy.plainWhy(n, lang), n.excerpt)
        }
        val family = familyName?.trim()?.takeIf { it.isNotEmpty() }

        fun view(card: WidgetCard): WidgetView {
            val live = card == WidgetCard.LIVE
            val adviceCard = card == WidgetCard.ALERT || card == WidgetCard.HISTORY
            val alert = if (card == WidgetCard.ALERT) sit.alert else null
            return WidgetView(
                card = card,
                tier = band,
                tierLabel = WidgetCopy.tier(lang, band),
                contextScore = surface.contextScore,
                languageScore = surface.languageScore,
                risk = surface.risk,
                callerTitle = if (live) caller?.first else null,
                callerSubtitle = if (live) caller?.second else null,
                callElapsedSec = if (live) elapsed else null,
                languageLabel = if (live) WidgetCopy.languageName(lang, surface.detectedLanguage) else null,
                transcript = if (live) lines else emptyList(),
                listeningHint = if (live && sit.unknownCall && lines.isEmpty()) WidgetCopy.listening(lang) else null,
                reasons = if (live) surface.reasons.take(3) else emptyList(),
                alertTitle = if (card == WidgetCard.ALERT) WidgetCopy.alertTitle(lang) else null,
                alertSource = alert?.source,
                alertExcerpt = alert?.excerpt,
                alertWhy = alert?.let { WidgetCopy.plainWhy(it, lang) },
                footnote = if (live && sit.alert != null) WidgetCopy.footnote(lang, WidgetCopy.plainWhy(sit.alert, lang)) else null,
                recentAlerts = recent,
                adviceTitle = if (adviceCard) WidgetCopy.adviceTitle(lang) else null,
                advice = if (adviceCard) WidgetCopy.advice(lang) else emptyList(),
                paymentRiskLabel = WidgetCopy.paymentRisk(lang),
                transcriptTitle = WidgetCopy.transcriptTitle(lang),
                reasonsTitle = WidgetCopy.reasonsTitle(lang),
                whyTitle = WidgetCopy.whyTitle(lang),
                helplineLabel = WidgetCopy.callHelpline(lang),
                familyLabel = family?.let { WidgetCopy.callFamily(lang, it) },
                dismissLabel = WidgetCopy.dismiss(lang),
                collapseLabel = WidgetCopy.collapse(lang),
                backLabel = WidgetCopy.back(lang),
                recentLabel = WidgetCopy.recent(lang),
                alertCount = surface.scamNotices.size,
                scoreLine = RiskLabels.compact(lang, surface.tierLevel, surface.contextScore, surface.languageScore, surface.risk),
            )
        }

        // The existing full-screen pause already owns a tier 2/3 payment. The bubble stays out of its way.
        if (fullScreenPauseShowing && surface.tierLevel >= 2) return view(WidgetCard.BUBBLE)

        val epochNow = epoch(sit)
        if (latch.historyOpen && latch.historyEpoch == epochNow && surface.scamNotices.isNotEmpty()) {
            return view(WidgetCard.HISTORY)
        }
        if (latch.quiet?.let { quietCovers(it, sit) } == true) return view(WidgetCard.BUBBLE)
        if (sit.unknownCall || surface.tierLevel >= 1) return view(WidgetCard.LIVE)
        if (sit.alert != null) return view(WidgetCard.ALERT)
        return view(WidgetCard.BUBBLE)
    }

    fun collapse(latch: WidgetUiLatch, surface: WidgetSurface): WidgetUiLatch {
        val sit = situation(surface, latch)
        return latch.copy(
            quiet = Quiet(sit.callKey, sit.alertId, sit.tier, hardDismiss = false),
            historyOpen = false,
        )
    }

    fun dismiss(latch: WidgetUiLatch, surface: WidgetSurface): WidgetUiLatch {
        val card = project(surface, latch).card
        val sit = situation(surface, latch)
        val dismissed = if (card == WidgetCard.ALERT && sit.alertId != null) latch.dismissedAlertIds + sit.alertId else latch.dismissedAlertIds
        val next = latch.copy(dismissedAlertIds = dismissed, historyOpen = false)
        val after = situation(surface, next)
        return next.copy(quiet = Quiet(after.callKey, after.alertId, after.tier, hardDismiss = true))
    }

    /**
     * Tap on the calm bubble. A collapse reopens. A dismiss does not bring the same incident back;
     * if there is history, the tap opens that instead.
     */
    fun bubbleTap(
        latch: WidgetUiLatch,
        surface: WidgetSurface,
        fullScreenPauseShowing: Boolean = false,
    ): WidgetUiLatch {
        if (fullScreenPauseShowing) return latch
        if (project(surface, latch).card != WidgetCard.BUBBLE) return latch
        val sit = situation(surface, latch)
        val quiet = latch.quiet
        val covered = quiet != null && quietCovers(quiet, sit)
        val attention = sit.unknownCall || sit.tier >= 1 || sit.alert != null
        if (attention && quiet != null && covered && quiet.hardDismiss) {
            return if (surface.scamNotices.isNotEmpty()) latch.copy(historyOpen = true, historyEpoch = epoch(sit)) else latch
        }
        if (attention && (!covered || quiet?.hardDismiss == false)) {
            return latch.copy(quiet = null, historyOpen = false)
        }
        if (surface.scamNotices.isNotEmpty()) {
            return if (latch.historyOpen) latch.copy(historyOpen = false)
            else latch.copy(historyOpen = true, historyEpoch = epoch(sit))
        }
        return latch
    }

    fun openHistory(latch: WidgetUiLatch, surface: WidgetSurface): WidgetUiLatch {
        if (surface.scamNotices.isEmpty()) return latch
        return latch.copy(historyOpen = true, historyEpoch = epoch(situation(surface, latch)))
    }

    fun closeHistory(latch: WidgetUiLatch): WidgetUiLatch = latch.copy(historyOpen = false)
}

/** Case-insensitive phrase marks. Quotes shorter than 3 characters are ignored so a stray letter cannot paint the line. */
fun highlightSpans(text: String, quotes: List<String>): List<HighlightSpan> {
    if (text.isEmpty() || quotes.isEmpty()) return emptyList()
    val lower = text.lowercase()
    val found = mutableListOf<HighlightSpan>()
    for (quote in quotes) {
        val needle = quote.trim().lowercase()
        if (needle.length < 3) continue
        var from = 0
        while (from < lower.length) {
            val at = lower.indexOf(needle, from)
            if (at < 0) break
            val end = (at + needle.length).coerceAtMost(text.length)
            if (end > at) found += HighlightSpan(at, end)
            from = at + needle.length
        }
    }
    return found.sortedBy { it.start }.fold(mutableListOf<HighlightSpan>()) { acc, span ->
        val last = acc.lastOrNull()
        when {
            last == null || span.start >= last.endExclusive -> acc += span
            span.endExclusive > last.endExclusive -> acc[acc.lastIndex] = HighlightSpan(last.start, span.endExclusive)
        }
        acc
    }
}

private data class Situation(
    val callKey: String?,
    val alertId: String?,
    val tier: Int,
    val unknownCall: Boolean,
    val alert: ScamNotice?,
)

private fun situation(surface: WidgetSurface, latch: WidgetUiLatch): Situation {
    val alert = surface.scamNotices.firstOrNull { it.id !in latch.dismissedAlertIds }
    val callKey = if (surface.callLive) {
        "${surface.callStartedAtMs}|${surface.callerNumber.orEmpty()}|${surface.callerInContacts}"
    } else null
    return Situation(
        callKey = callKey,
        alertId = alert?.id,
        tier = surface.tierLevel.coerceIn(0, 3),
        unknownCall = surface.callLive && !surface.callerInContacts,
        alert = alert,
    )
}

private fun epoch(sit: Situation) = "${sit.callKey}|${sit.alertId}|${sit.tier}"

private fun quietCovers(quiet: Quiet, sit: Situation): Boolean =
    sit.tier <= quiet.tier && sit.callKey == quiet.callKey && sit.alertId == quiet.alertId

private fun callerLines(surface: WidgetSurface, lang: Lang): Pair<String, String?>? {
    if (!surface.callLive) return null
    val name = surface.callerName?.trim()?.takeIf { it.isNotEmpty() }
    val number = surface.callerNumber?.trim().orEmpty()
    val shownNumber = number.takeIf { it.count { ch -> ch.isDigit() } >= 3 }
    if (surface.callerInContacts && name != null) return name to shownNumber
    return WidgetCopy.unknownCaller(lang) to shownNumber
}
