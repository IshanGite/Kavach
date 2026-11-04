package com.kavach.core.analysis

/**
 * How much of a transcript is real, recognisable speech. On a faint or noisy call Whisper hallucinates
 * ("PAPPAPPAPP...", "वाँ वाँ वाँ", one stray word); that is noise, not evidence, and must never read as "benign".
 * Pure and deterministic: no logging, no clock. Compared on tokens, never on meaning.
 */
object TranscriptQuality {
    /** A token seen more often than this in a transcript adds nothing further to the count. */
    private const val MAX_PER_TOKEN = 3

    private val SPLIT = Regex("""[\s,.!?;:।|"'()\[\]{}\-_/\\]+""")

    /** Plausible words in order, with junk tokens dropped and repeated units collapsed. */
    fun cleanWords(text: String): List<String> {
        val tokens = text.lowercase().split(SPLIT).filter { it.isNotEmpty() }.filter(::isPlausibleToken)
        val collapsed = collapseRepeats(tokens)
        val seen = HashMap<String, Int>()
        return collapsed.filter { w ->
            val n = (seen[w] ?: 0) + 1
            seen[w] = n
            n <= MAX_PER_TOKEN
        }
    }

    fun cleanWordCount(text: String): Int = cleanWords(text).size

    /** [clean] recognisable words out of [raw] whitespace/punctuation separated tokens. */
    data class Stats(val raw: Int, val clean: Int) {
        val cleanFraction: Double get() = if (raw == 0) 0.0 else clean.toDouble() / raw
    }

    fun stats(text: String): Stats = Stats(text.split(SPLIT).count { it.isNotEmpty() }, cleanWordCount(text))

    /** False for tokens that cannot be a spoken word: one letter spammed, a short unit repeated, a long run of one letter. */
    fun isPlausibleToken(token: String): Boolean {
        if (token.none { it.isLetterOrDigit() }) return false
        if (token.all { it.isDigit() }) return token.length <= 9
        if (token.any { it.isDigit() } && token.any { it.isLetter() }) return false
        if (token.length >= 4 && token.toSet().size == 1) return false
        var run = 1
        for (i in 1 until token.length) {
            run = if (token[i] == token[i - 1]) run + 1 else 1
            if (run >= 4) return false
        }
        if (token.length >= 6 && repeatsUnit(token)) return false
        return true
    }

    /** True when the whole token is one 1..4 character unit repeated at least three times (a partial last unit is allowed). */
    private fun repeatsUnit(t: String): Boolean {
        for (p in 1..4) {
            if (t.length < 3 * p) continue
            if ((p until t.length).all { t[it] == t[it - p] }) return true
        }
        return false
    }

    /** "ho jayega ho jayega ho jayega" counts once: a unit of 1..3 tokens repeated back to back is collapsed. */
    private fun collapseRepeats(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        for (tok in tokens) {
            out += tok
            var changed = true
            while (changed) {
                changed = false
                for (k in 1..3) {
                    if (out.size >= 2 * k && (0 until k).all { out[out.size - 1 - it] == out[out.size - 1 - k - it] }) {
                        repeat(k) { out.removeAt(out.size - 1) }
                        changed = true
                        break
                    }
                }
            }
        }
        return out
    }
}
