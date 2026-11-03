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
        assertEquals("whisper (detected: hi)", whisperHomeLabel(" HI "))
        assertEquals("whisper", whisperHomeLabel("not a language"))
    }

    @Test
    fun `window note uses a dot and the audio duration`() {
        assertEquals("asr window 5.0s decoded in 1234 ms", asrWindowNote(80_000, 16_000, 1234))
        assertEquals("asr window 0.0s decoded in 10 ms", asrWindowNote(80_000, 0, 10))
    }

    @Test
    fun `base drops to tiny only when decode outlasts the window`() {
        assertFalse(whisperDecodeSlowerThanWindow(5_000, 80_000, 16_000))
        assertTrue(whisperDecodeSlowerThanWindow(5_001, 80_000, 16_000))
        assertFalse(whisperDecodeSlowerThanWindow(-1, 80_000, 16_000))
        assertFalse(whisperDecodeSlowerThanWindow(9_000, 0, 16_000))
    }

    @Test
    fun `engine choice stays vosk unless the setting is whisper`() {
        assertEquals("vosk", normalizeAsrEngine(null))
        assertEquals("vosk", normalizeAsrEngine(""))
        assertEquals("vosk", normalizeAsrEngine("Vosk"))
        assertEquals("whisper", normalizeAsrEngine("whisper"))
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
                "ASR ready: whisper base int8, 4 threads, tiny on disk",
                whisperReadyDetail(set.base!!, tinyOnDisk = true, threads = 4),
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
                "ASR ready: whisper base int8, 4 threads, tiny not on disk",
                whisperReadyDetail(generic.base!!, tinyOnDisk = false, threads = 4),
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
                "ASR ready: whisper tiny int8, 4 threads (base not on disk)",
                whisperReadyDetail(set.active!!, tinyOnDisk = false, threads = 4),
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
