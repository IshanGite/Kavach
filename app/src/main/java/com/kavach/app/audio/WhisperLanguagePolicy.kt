package com.kavach.app.audio

/**
 * Pure rules for which language Whisper decodes in. Kavach supports Hindi, English and Marathi.
 * Auto-detect on noisy Hinglish drifts to tr/fr/my and yields gibberish, so a detection outside this
 * set triggers a re-decode in Hindi, and a per-call sticky language stops later windows re-detecting.
 */
internal object WhisperLanguagePolicy {
    /** Settings value for auto. */
    const val AUTO = "auto"

    private val ALLOWED = setOf("hi", "en", "mr")

    /** Maps a detected code to a supported one: Urdu is Hindi, anything else is null. */
    fun supported(code: String?): String? {
        val c = normalizeWhisperLang(code)
        return when (c) {
            "hi", "ur" -> "hi"
            "en", "mr" -> c
            else -> null
        }
    }

    /** Setting value to a forced decode language, or "" for auto. */
    fun forced(setting: String?): String = setting?.trim()?.lowercase()?.takeIf { it in ALLOWED } ?: ""

    /** Language to hand the decoder: the forced one, else the sticky one, else "" (auto-detect). */
    fun decodeLanguage(setting: String?, sticky: String): String =
        forced(setting).ifEmpty { sticky }

    /** True when a first auto-detect decode's result should be thrown away and re-run in Hindi. */
    fun needsHindiRedecode(decodeLang: String, detected: String?): Boolean =
        decodeLang.isEmpty() && supported(detected) == null

    /** Re-decode only if it should fit in the window: the first decode took at most half the audio. */
    fun redecodeFits(firstDecodeMs: Long, windowMs: Long): Boolean =
        windowMs > 0 && firstDecodeMs >= 0 && firstDecodeMs * 2 <= windowMs

    /** Majority of the last [n] accepted windows. */
    class Sticky(private val n: Int = 3) {
        private val recent = ArrayDeque<String>()

        fun accept(lang: String) {
            val s = supported(lang) ?: return
            recent.addLast(s)
            while (recent.size > n) recent.removeFirst()
        }

        /** Needs at least 2 windows and a strict majority, else "" (keep auto-detecting). */
        fun current(): String {
            if (recent.size < 2) return ""
            val top = recent.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return ""
            return if (top.value * 2 > recent.size) top.key else ""
        }

        fun reset() = recent.clear()
    }

    /** True for empty text, character spam ("Rdbjjjjjjjjj") or one token repeated many times. */
    fun isJunk(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return true
        if (t.none { it.isLetterOrDigit() }) return true
        var run = 1
        for (i in 1 until t.length) {
            run = if (t[i] == t[i - 1]) run + 1 else 1
            if (run >= 6 && t[i].isLetter()) return true
        }
        val tokens = t.lowercase().split(Regex("[\\s,.!?।]+")).filter { it.isNotEmpty() }
        if (tokens.size >= 5) {
            val top = tokens.groupingBy { it }.eachCount().maxOf { it.value }
            if (top * 10 >= tokens.size * 7) return true
        }
        return false
    }
}
