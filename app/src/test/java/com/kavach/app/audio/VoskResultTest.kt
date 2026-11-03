package com.kavach.app.audio

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoskResultTest {
    @Test
    fun `parser keeps text and mean word confidence, and drops blanks and unk`() {
        val parsed = parseVoskUtterance(
            """{"result":[{"conf":0.9,"word":"digital"},{"conf":0.5,"word":"arrest"}],"text":" digital arrest "}""",
        )
        assertEquals("digital arrest", parsed?.text)
        assertEquals(0.7f, parsed!!.confidence, 0.001f)
        assertNull(parseVoskUtterance("""{"text":"  "}"""))
        assertNull(parseVoskUtterance("""{"text":"[unk]"}"""))
        assertEquals("digital arrest", parseVoskUtterance("""{"text":"digital [unk] arrest"}""")?.text)
        assertNull(parseVoskUtterance("not-json"))
    }

    @Test
    fun `merge weights confidence by word count`() {
        val merged = mergeUtterances(listOf(VoskUtterance("digital arrest", 1f), VoskUtterance("now", 0f)))
        assertEquals("digital arrest now", merged?.text)
        assertEquals(2f / 3f, merged!!.confidence, 0.001f)
        assertNull(mergeUtterances(emptyList()))
    }

    @Test
    fun `language HI selects hi and everything else selects en-in`() {
        assertEquals("hi", preferModelId("HI"))
        assertEquals("en-in", preferModelId("EN"))
        assertEquals("en-in", preferModelId(""))
    }

    @Test
    fun `preferFirst puts the requested model ahead and leaves an unknown id alone`() {
        val ids = listOf("en-in", "hi")
        assertEquals(listOf("hi", "en-in"), preferFirst(ids, "hi") { it })
        assertEquals(ids, preferFirst(ids, "en-in") { it })
        assertEquals(ids, preferFirst(ids, "missing") { it })
    }

    @Test
    fun `missing model dirs are skipped and a nested unzip still counts`() {
        val root = Files.createTempDirectory("kavach-vosk").toFile()
        try {
            assertTrue(resolveVoskModels(root).isEmpty())
            fakeModel(File(root, "vosk-model-small-en-in-0.4"))
            // am/ without conf/ or graph/ is not a model (partial copy).
            val broken = File(root, "vosk-model-small-hi-0.22")
            broken.mkdirs()
            File(broken, "am").mkdir()
            assertEquals(listOf("en-in"), resolveVoskModels(root).map { it.first })

            broken.deleteRecursively()
            fakeModel(File(broken, "vosk-model-small-hi-0.22"))
            assertEquals(listOf("en-in", "hi"), resolveVoskModels(root).map { it.first })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `no model reports the home status string`() {
        assertEquals("none (push Vosk model)", NoopAsr.name)
    }

    private fun fakeModel(dir: File) {
        File(dir, "am").mkdirs()
        File(dir, "conf").mkdirs()
    }
}
