package com.kavach.app.engine

/**
 * Snapshot the floating widget reads. The risk engine still decides the tier; this only carries
 * what it already decided, plus scam-message verdicts the UI needs for "Recent alerts".
 * Nothing here is logged or sent off the phone.
 */
data class WidgetSurface(
    val atMs: Long = 0,
    val callLive: Boolean = false,
    val callerNumber: String? = null,
    val callerInContacts: Boolean = false,
    val callerName: String? = null,
    val callStartedAtMs: Long? = null,
    /** Raw recogniser code (`hi`, `en`, `<|mr|>`). Blank or "auto" means not detected yet. */
    val detectedLanguage: String? = null,
    /** [com.kavach.core.orchestrator.Decision.tier] level, 0..3. Not recomputed from the scores. */
    val tierLevel: Int = 0,
    val contextScore: Int = 0,
    val languageScore: Int? = null,
    val risk: Int = 0,
    val transcript: List<TranscriptLine> = emptyList(),
    val evidenceQuotes: List<String> = emptyList(),
    /** Plain reasons from the intervention plan. Empty while the tier is still 0. */
    val reasons: List<String> = emptyList(),
    /** Newest first. Capped by [pushNotice]. */
    val scamNotices: List<ScamNotice> = emptyList(),
)

data class TranscriptLine(val text: String, val atMs: Long)

data class ScamNotice(
    val id: String,
    val atMs: Long,
    val source: String,
    val excerpt: String,
    val why: String,
    val typeName: String,
)

/**
 * A scam verdict becomes one history row. Non-scams (a real bank OTP, low confidence) return null.
 * The same id is ignored so a re-posted notification does not stack copies.
 */
fun noticeFrom(
    atMs: Long,
    source: String,
    text: String,
    isScam: Boolean,
    confidence: Double,
    reason: String,
    typeName: String,
): ScamNotice? {
    if (!isScam || confidence < 0.5) return null
    val excerpt = text.replace(Regex("\\s+"), " ").trim().take(160)
    val src = source.trim().ifBlank { "Message" }
    val why = reason.trim().ifBlank { typeName.lowercase().replace('_', ' ') }
    return ScamNotice(
        id = "$atMs|$src|${excerpt.hashCode()}",
        atMs = atMs,
        source = src,
        excerpt = excerpt,
        why = why,
        typeName = typeName,
    )
}

fun pushNotice(existing: List<ScamNotice>, incoming: ScamNotice, limit: Int = 8): List<ScamNotice> {
    if (limit <= 0) return emptyList()
    if (existing.any { it.id == incoming.id }) return existing
    return (listOf(incoming) + existing).take(limit)
}
