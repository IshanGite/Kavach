package com.kavach.app.audio

import java.io.File
import java.util.Locale

/**
 * On-disk layout for the sherpa-onnx Whisper weights. The files are pushed with adb into
 * `getExternalFilesDir(null)/sherpa-whisper/` (or the unpacked `sherpa-onnx-whisper-*` folder).
 * They are never shipped in the APK and never committed.
 *
 * int8 is preferred. `base` is the model we start on; `tiny` is the fallback when a window
 * takes longer to decode than the audio it contains.
 */
internal const val WHISPER_DIR = "sherpa-whisper"

/** Upper end of the 2–4 thread range, so base has a chance to finish inside a 5 s window on a Snapdragon 855. */
internal const val WHISPER_THREADS = 4

internal data class WhisperFiles(
    val size: String,
    val encoder: File,
    val decoder: File,
    val tokens: File,
) {
    val int8: Boolean get() = encoder.name.contains("int8") && decoder.name.contains("int8")
}

internal data class WhisperModelSet(val base: WhisperFiles?, val tiny: WhisperFiles?) {
    /** Base when it is on disk, otherwise tiny. Null when nothing usable was pushed. */
    val active: WhisperFiles? get() = base ?: tiny
}

internal fun normalizeAsrEngine(raw: String?): String = if (raw == "whisper") "whisper" else "vosk"

internal fun whisperHomeLabel(detected: String): String {
    val lang = normalizeWhisperLang(detected)
    return if (lang.isEmpty()) "whisper" else "whisper (detected: $lang)"
}

/** Whisper language id, or blank. Accepts a bare code (`mr`) and a Whisper token (`<|mr|>`). */
internal fun normalizeWhisperLang(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var t = raw.trim().lowercase(Locale.US)
    if (t.startsWith("<|") && t.endsWith("|>") && t.length > 4) t = t.substring(2, t.length - 2)
    if (t == "auto" || t == "none" || t == "und") return ""
    return if (LANG_CODE.matches(t)) t else ""
}

/**
 * Debug-log line for one window. [samples] / [sampleRateHz] is the audio duration, not the clock time.
 * A full capture window is `asr window 5.0s decoded in X ms`.
 */
internal fun asrWindowNote(samples: Int, sampleRateHz: Int, elapsedMs: Long): String {
    val seconds = if (sampleRateHz <= 0) 0.0 else samples.toDouble() / sampleRateHz.toDouble()
    return String.format(Locale.US, "asr window %.1fs decoded in %d ms", seconds, elapsedMs)
}

/** True when decode took longer than the audio it was given, so the next window should use tiny. */
internal fun whisperDecodeSlowerThanWindow(elapsedMs: Long, samples: Int, sampleRateHz: Int): Boolean {
    if (elapsedMs < 0 || samples <= 0 || sampleRateHz <= 0) return false
    val windowMs = samples * 1000.0 / sampleRateHz
    return elapsedMs > windowMs
}

internal fun whisperReadyDetail(active: WhisperFiles, tinyOnDisk: Boolean, threads: Int): String {
    val quant = if (active.int8) "int8" else "fp32"
    return if (active.size == "base") {
        "ASR ready: whisper base $quant, $threads threads, tiny " + if (tinyOnDisk) "on disk" else "not on disk"
    } else {
        "ASR ready: whisper ${active.size} $quant, $threads threads (base not on disk)"
    }
}

internal fun resolveWhisperModels(root: File): WhisperModelSet {
    val dirs = whisperCandidateDirs(root)
    val base = findNamedWhisper(dirs, "base") ?: findGenericWhisper(root)
    val tiny = findNamedWhisper(dirs, "tiny")
    return WhisperModelSet(base, tiny)
}

internal fun whisperCandidateDirs(root: File): List<File> {
    val rels = listOf(
        WHISPER_DIR,
        "$WHISPER_DIR/sherpa-onnx-whisper-base",
        "$WHISPER_DIR/sherpa-onnx-whisper-tiny",
        "$WHISPER_DIR/sherpa-onnx-whisper-base/sherpa-onnx-whisper-base",
        "$WHISPER_DIR/sherpa-onnx-whisper-tiny/sherpa-onnx-whisper-tiny",
        "sherpa-onnx-whisper-base",
        "sherpa-onnx-whisper-tiny",
        "sherpa-onnx-whisper-base/sherpa-onnx-whisper-base",
        "sherpa-onnx-whisper-tiny/sherpa-onnx-whisper-tiny",
    )
    val seen = HashSet<String>()
    val out = ArrayList<File>(rels.size)
    for (rel in rels) {
        val dir = File(root, rel)
        if (!dir.isDirectory) continue
        if (seen.add(dir.absolutePath)) out += dir
    }
    return out
}

private val LANG_CODE = Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?")

private fun findNamedWhisper(dirs: List<File>, size: String): WhisperFiles? {
    val encoders = listOf("$size-encoder.int8.onnx", "$size-encoder.onnx")
    val decoders = listOf("$size-decoder.int8.onnx", "$size-decoder.onnx")
    val tokens = listOf("$size-tokens.txt")
    for (dir in dirs) {
        val encoder = firstFile(dir, encoders) ?: continue
        val decoder = firstFile(dir, decoders) ?: continue
        val tok = firstFile(dir, tokens) ?: continue
        return WhisperFiles(size, encoder, decoder, tok)
    }
    return null
}

/** `encoder` / `decoder` / `tokens` dropped straight into `sherpa-whisper/`, treated as the base model. */
private fun findGenericWhisper(root: File): WhisperFiles? {
    val dir = File(root, WHISPER_DIR)
    if (!dir.isDirectory) return null
    val encoder = firstFile(dir, listOf("encoder.int8.onnx", "encoder.onnx")) ?: return null
    val decoder = firstFile(dir, listOf("decoder.int8.onnx", "decoder.onnx")) ?: return null
    val tokens = firstFile(dir, listOf("tokens.txt")) ?: return null
    return WhisperFiles("base", encoder, decoder, tokens)
}

private fun firstFile(dir: File, names: List<String>): File? {
    for (name in names) {
        val f = File(dir, name)
        if (f.isFile) return f
    }
    return null
}
