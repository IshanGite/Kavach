package com.kavach.app.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/** One finished Vosk utterance. Confidence is the mean of per-word scores, or 0 when Vosk omitted them. */
internal data class VoskUtterance(val text: String, val confidence: Float)

/**
 * Model folders pushed with adb. Order is the fallback order when the preferred id is not installed.
 * Each zip from alphacephei.com unpacks to a directory of this name containing `am/` and `conf/`.
 */
internal val VOSK_MODEL_SPECS = listOf(
    "en-in" to "vosk-model-small-en-in-0.4",
    "hi" to "vosk-model-small-hi-0.22",
)

/** Settings language "HI" selects the Hindi model. Anything else, including the default "EN", selects Indian English. */
internal fun preferModelId(language: String): String = if (language == "HI") "hi" else "en-in"

internal fun isVoskModelDir(dir: File): Boolean =
    dir.isDirectory && File(dir, "am").isDirectory && (File(dir, "conf").isDirectory || File(dir, "graph").isDirectory)

/**
 * Models that are actually on disk under [root] (the app's external files dir).
 * A missing folder is skipped. A zip that was unpacked one level too deep (`name/name/am`) is accepted.
 */
internal fun resolveVoskModels(root: File): List<Pair<String, File>> {
    val found = ArrayList<Pair<String, File>>(VOSK_MODEL_SPECS.size)
    for ((id, name) in VOSK_MODEL_SPECS) {
        val dir = File(root, name)
        val resolved = when {
            isVoskModelDir(dir) -> dir
            isVoskModelDir(File(dir, name)) -> File(dir, name)
            else -> null
        }
        if (resolved != null) found += id to resolved
    }
    return found
}

/** Preferred model first, then the others in spec order. An unknown id leaves the list unchanged. */
internal fun <T> preferFirst(items: List<T>, preferId: String, idOf: (T) -> String): List<T> {
    if (items.size < 2) return items
    val (hit, rest) = items.partition { idOf(it) == preferId }
    return if (hit.isEmpty()) items else hit + rest
}

/**
 * Parses one Vosk `result` or `finalResult` JSON object.
 * Blank text and a lone `[unk]` become null so the capture loop does not emit an empty transcript.
 */
internal fun parseVoskUtterance(json: String): VoskUtterance? {
    val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    val raw = (obj["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val text = raw.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "[unk]" }.joinToString(" ")
    if (text.isEmpty()) return null
    val confs = (obj["result"] as? JsonArray)?.mapNotNull { el ->
        val word = el as? JsonObject ?: return@mapNotNull null
        (word["conf"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
    }.orEmpty()
    val confidence = if (confs.isEmpty()) 0f else confs.sum() / confs.size
    return VoskUtterance(text, confidence)
}

/** Joins endpointed pieces from one window. Confidence is weighted by word count. */
internal fun mergeUtterances(parts: List<VoskUtterance>): VoskUtterance? {
    if (parts.isEmpty()) return null
    var weighted = 0.0
    var words = 0
    for (p in parts) {
        val n = p.text.split(' ').count { it.isNotEmpty() }
        weighted += p.confidence.toDouble() * n
        words += n
    }
    val confidence = if (words == 0) 0f else (weighted / words).toFloat()
    return VoskUtterance(parts.joinToString(" ") { it.text }, confidence)
}
