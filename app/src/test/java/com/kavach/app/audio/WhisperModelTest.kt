package com.kavach.app.audio

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhisperModelTest {
    @Test
    fun `home line is whisper until a language id arrives`() {
        assertEquals("whisper", whisperHomeLabel(""))
        assertEquals("whisper", whisperHomeLabel("auto"))
        assertEquals("whisper (detected: mr)", whisperHomeLabel("mr"))
        assertEquals("whisper (detected: mr)", whisperHomeLabel("<|mr|>"))
        assertEquals("whisper small", whisperHomeLabel("", "small"))
        assertEquals("whisper small (detected: hi)", whisperHomeLabel("hi", "small"))
        assertEquals("whisper (detected: hi)", whisperHomeLabel(" HI "))
        assertEquals("whisper", whisperHomeLabel("not a language"))
    }

    @Test
    fun `window note uses a dot and the audio duration`() {
        assertEquals("asr window 5.0s decoded in 1234 ms", asrWindowNote(80_000, 16_000, 1234))
        assertEquals("asr window 0.0s decoded in 10 ms", asrWindowNote(80_000, 0, 10))
    }

    @Test
    fun `overrun is judged against the stride, not the window`() {
        // 8 s window: stride is 4.5 s
        assertFalse(whisperDecodeOverranStride(4_500, 128_000, 16_000))
        assertTrue(whisperDecodeOverranStride(4_501, 128_000, 16_000))
        // 5 s fallback window: the window itself
        assertFalse(whisperDecodeOverranStride(5_000, 80_000, 16_000))
        assertTrue(whisperDecodeOverranStride(5_001, 80_000, 16_000))
        assertFalse(whisperDecodeOverranStride(-1, 128_000, 16_000))
        assertFalse(whisperDecodeOverranStride(9_000, 0, 16_000))
    }

    @Test
    fun `step down needs two overruns in a row and one fast window clears it`() {
        val c = StepDownCounter()
        assertFalse(c.record(true))
        assertFalse(c.record(false))
        assertFalse(c.record(true))
        assertTrue(c.record(true))
        assertFalse(c.record(true))
        c.reset()
        assertFalse(c.record(true))
    }

    @Test
    fun `size ladder is small then base then tiny`() {
        assertEquals("base", nextWhisperSizeDown("small"))
        assertEquals("tiny", nextWhisperSizeDown("base"))
        assertNull(nextWhisperSizeDown("tiny"))
    }

    @Test
    fun `engine choice stays vosk unless the setting is whisper`() {
        assertEquals("vosk", normalizeAsrEngine(null))
        assertEquals("vosk", normalizeAsrEngine(""))
        assertEquals("vosk", normalizeAsrEngine("Vosk"))
        assertEquals("whisper", normalizeAsrEngine("whisper"))
    }

    @Test
    fun `small is preferred over base and tiny, and is read from an unpacked folder`() {
        val root = Files.createTempDirectory("kavach-whisper-small").toFile()
        try {
            val dir = File(root, "sherpa-whisper")
            touch(dir, "base-encoder.int8.onnx"); touch(dir, "base-decoder.int8.onnx"); touch(dir, "base-tokens.txt")
            touch(dir, "tiny-encoder.int8.onnx"); touch(dir, "tiny-decoder.int8.onnx"); touch(dir, "tiny-tokens.txt")
            assertEquals("base", resolveWhisperModels(root).active?.size)
            val nested = File(root, "sherpa-whisper/sherpa-onnx-whisper-small")
            touch(nested, "small-encoder.int8.onnx"); touch(nested, "small-decoder.int8.onnx"); touch(nested, "small-tokens.txt")
            val set = resolveWhisperModels(root)
            assertEquals("small", set.active?.size)
            assertEquals(listOf("small", "base", "tiny"), set.ordered.map { it.size })
            assertEquals(
                "ASR ready: whisper small int8, 4 threads, step-down: base, tiny",
                whisperReadyDetail(set.small!!, listOf("base", "tiny"), 4),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `int8 base is preferred and tiny is kept beside it`() {
        val root = Files.createTempDirectory("kavach-whisper").toFile()
        try {
            val dir = File(root, "sherpa-whisper")
            touch(dir, "base-encoder.onnx")
            touch(dir, "base-encoder.int8.onnx")
            touch(dir, "base-decoder.int8.onnx")
            touch(dir, "base-tokens.txt")
            touch(dir, "tiny-encoder.int8.onnx")
            touch(dir, "tiny-decoder.int8.onnx")
            touch(dir, "tiny-tokens.txt")
            val set = resolveWhisperModels(root)
            assertEquals("base-encoder.int8.onnx", set.base?.encoder?.name)
            assertEquals("base-decoder.int8.onnx", set.base?.decoder?.name)
            assertTrue(set.base!!.int8)
            assertEquals("tiny", set.tiny?.size)
            assertEquals(set.base, set.active)
            assertEquals(
                "ASR ready: whisper base int8, 4 threads, step-down: tiny",
                whisperReadyDetail(set.base!!, listOf("tiny"), 4),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a nested unzip and a generic encoder-decoder-tokens trio both count`() {
        val root = Files.createTempDirectory("kavach-whisper-nested").toFile()
        try {
            assertNull(resolveWhisperModels(root).active)
            val nested = File(root, "sherpa-whisper/sherpa-onnx-whisper-base")
            touch(nested, "base-encoder.int8.onnx")
            touch(nested, "base-decoder.int8.onnx")
            // tokens missing: not a model
            assertNull(resolveWhisperModels(root).base)
            touch(nested, "base-tokens.txt")
            assertEquals("base", resolveWhisperModels(root).active?.size)

            nested.deleteRecursively()
            val loose = File(root, "sherpa-whisper")
            touch(loose, "encoder.int8.onnx")
            touch(loose, "decoder.int8.onnx")
            touch(loose, "tokens.txt")
            val generic = resolveWhisperModels(root)
            assertEquals("base", generic.base?.size)
            assertNull(generic.tiny)
            assertTrue(generic.base!!.int8)
            assertEquals(
                "ASR ready: whisper base int8, 4 threads, no smaller model on disk",
                whisperReadyDetail(generic.base!!, emptyList(), 4),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tiny alone is the active model`() {
        val root = Files.createTempDirectory("kavach-whisper-tiny").toFile()
        try {
            val dir = File(root, "sherpa-onnx-whisper-tiny/sherpa-onnx-whisper-tiny")
            touch(dir, "tiny-encoder.int8.onnx")
            touch(dir, "tiny-decoder.int8.onnx")
            touch(dir, "tiny-tokens.txt")
            val set = resolveWhisperModels(root)
            assertNull(set.base)
            assertEquals("tiny", set.active?.size)
            assertEquals(
                "ASR ready: whisper tiny int8, 4 threads, no smaller model on disk",
                whisperReadyDetail(set.active!!, emptyList(), 4),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun touch(dir: File, name: String) {
        dir.mkdirs()
        File(dir, name).writeText("x")
    }
}
