package com.kavach.app.audio

/**
 * Overlapping ASR windows decode the same audio twice. [dropOverlap] removes from [next] the longest word prefix that equals a word
 * suffix of [prev] (case and punctuation insensitive, at least [minWords] words, or all of [next]). Returns the new words only,
 * possibly empty. Pure.
 */
fun dropOverlap(prev: String, next: String, minWords: Int = 2): String {
    val nw = next.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val pw = prev.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (nw.isEmpty() || pw.isEmpty()) return nw.joinToString(" ")
    fun norm(w: String) = w.lowercase().filter { it.isLetterOrDigit() }
    val pn = pw.map(::norm)
    val nn = nw.map(::norm)
    for (k in minOf(pn.size, nn.size) downTo 1) {
        if (k < minWords && k != nn.size) continue
        if (pn.subList(pn.size - k, pn.size) == nn.subList(0, k)) return nw.drop(k).joinToString(" ")
    }
    return nw.joinToString(" ")
}
