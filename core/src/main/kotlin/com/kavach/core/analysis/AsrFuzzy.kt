package com.kavach.core.analysis

/**
 * ASR-noise tolerant matching for the keyword analyst. On-device Whisper on a speakerphone call turns
 * "arrest" into "arested", "gireftar" into "girphtar", and so on. We normalise romanisation and allow one edit on
 * longer words. Deliberately conservative: only a short list of high-signal single words is fuzzy, tokens whose
 * canonical form is under 6 characters must match exactly (after normalisation), and known look-alikes are blocked.
 * Pure and deterministic: no logging, no clock.
 */
internal object AsrFuzzy {
    enum class Cat { AUTHORITY, THREAT, URGENCY, MONEY, INSTRUMENT, PAYMENT }

    /** Collapse romanisation variants: sh/s, v/w, ph/f, aa/a, ee/i, oo/u, doubled letters, z/j, q/k. */
    fun canon(word: String): String {
        var s = word.lowercase().filter { it.isLetter() }
        s = s.replace("sh", "s").replace("ph", "f").replace("kh", "k").replace("gh", "g").replace("ch", "c")
            .replace("w", "v").replace("z", "j").replace("q", "k")
            .replace("ee", "i").replace("ii", "i").replace("oo", "u").replace("uu", "u").replace("aa", "a")
            .replace("ai", "e").replace("ei", "e").replace("ou", "u")
        val sb = StringBuilder()
        for (c in s) if (sb.isEmpty() || sb.last() != c) sb.append(c)
        return sb.toString().trimEnd('h') // a trailing "h" is routinely dropped or added by the recogniser
    }

    private val TERMS: Map<Cat, List<String>> = mapOf(
        Cat.AUTHORITY to listOf("police", "polis", "inspector", "officer", "cbi", "magistrate", "narcotics"),
        Cat.THREAT to listOf(
            "arrest", "arrested", "arrestation", "arresting", "giraftar", "gireftar", "giraftari", "girftar", "girftari",
            "warrant", "laundering", "custody",
        ),
        Cat.URGENCY to listOf("abhi", "turant", "immediately", "urgent", "urgently", "jaldi", "fauran", "foran"),
        Cat.MONEY to listOf("rupees", "rupee", "rupaye", "rupaiye", "rupay", "paise", "paisa", "lakh", "hazaar", "hajar", "thousand"),
        Cat.INSTRUMENT to listOf("otp", "kyc", "aadhaar", "adhar", "card"),
        Cat.PAYMENT to listOf("transfer", "transferred"),
    )

    /** Words one edit away from a term but meaning something else. Compared on canonical form. */
    private val BLOCK: Set<String> =
        setOf("warranty", "warranties", "polices", "policy", "policies", "offices", "cards", "hazard").map { canon(it) }.toSet()

    private val CANON: List<Pair<Cat, String>> = TERMS.flatMap { (cat, terms) -> terms.map { cat to canon(it) } }

    fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        if (a.length == b.length) {
            return a.substring(i + 1) == b.substring(i + 1) ||
                (i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2))
        }
        return if (a.length > b.length) a.substring(i + 1) == b.substring(i) else b.substring(i + 1) == a.substring(i)
    }

    /** Fuzzy hits per category: the spoken tokens that matched. */
    fun scan(text: String): Map<Cat, List<String>> {
        val out = LinkedHashMap<Cat, MutableList<String>>()
        for (m in Regex("""[A-Za-z]+""").findAll(text)) {
            val tok = m.value
            val c = canon(tok)
            if (c.length < 2 || c in BLOCK) continue
            for ((cat, term) in CANON) {
                val ok = if (term.length >= 6 && c.length >= 6) withinOneEdit(c, term) else c == term
                if (ok) {
                    val l = out.getOrPut(cat) { mutableListOf() }
                    if (tok.lowercase() !in l) l.add(tok.lowercase())
                    break
                }
            }
        }
        return out
    }

    private fun devaCanon(s: String): String =
        s.replace("़", "").replace("ँ", "ं").replace("ऩ", "न").replace("ऱ", "र")
            .replace("क़", "क").replace("ख़", "ख").replace("ग़", "ग").replace("ज़", "ज")
            .replace("ड़", "ड").replace("ढ़", "ढ").replace("फ़", "फ").replace("य़", "य")
            .replace("ॅ", "े").replace("ॉ", "ो") // candra vowels as plain e / o

    private val DEVA: List<Pair<Cat, String>> = listOf(
        Cat.THREAT to "अरेस्ट", Cat.THREAT to "गिरफ्तार", Cat.THREAT to "वारंट",
        Cat.URGENCY to "अभी", Cat.URGENCY to "तुरंत", Cat.URGENCY to "तुरन्त",
        Cat.MONEY to "रुपये", Cat.MONEY to "रुपए", Cat.MONEY to "रूपये", Cat.MONEY to "रुपया", Cat.MONEY to "पैसे",
        Cat.PAYMENT to "ट्रांसफर", Cat.PAYMENT to "ट्रान्सफर",
        Cat.INSTRUMENT to "ओटीपी", Cat.INSTRUMENT to "केवाईसी", Cat.INSTRUMENT to "कार्ड",
    ).map { (c, t) -> c to devaCanon(t) }

    fun deva(text: String): Map<Cat, List<String>> {
        val t = devaCanon(text)
        val out = LinkedHashMap<Cat, MutableList<String>>()
        for ((cat, term) in DEVA) if (t.contains(term)) {
            val l = out.getOrPut(cat) { mutableListOf() }
            if (term !in l) l.add(term)
        }
        return out
    }
}
