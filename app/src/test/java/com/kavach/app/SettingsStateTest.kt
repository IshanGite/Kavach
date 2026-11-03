package com.kavach.app

import com.kavach.app.engine.ContactDto
import com.kavach.app.engine.SettingsState
import com.kavach.core.intervention.Lang
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStateTest {
    @Test
    fun `profile drops blank numbers and maps language`() {
        val s = SettingsState(
            userName = " ", language = "HI",
            guardians = listOf(ContactDto("Anjali", "+919800000001", "daughter"), ContactDto("Nobody", "", "")),
            family = listOf(ContactDto("", "+919800000002", "son")),
            safeWordSet = true,
        )
        val p = s.toProfile()
        assertEquals(Lang.HI, p.language); assertEquals("User", p.userName)
        assertEquals(listOf("Anjali"), p.guardians.map { it.name }); assertEquals("Family", p.familyContacts.single().name)
        assertTrue(p.familySafeWordSet)
    }

    @Test
    fun `safe defaults - cloud off, protection off, localhost model`() {
        val s = SettingsState()
        assertFalse(s.cloudEnabled); assertFalse(s.protectionEnabled); assertFalse(s.companionEnabled)
        assertTrue(s.showFloatingWidget)
        assertTrue(s.ollamaUrl.startsWith("http://127.0.0.1"))
        assertEquals(8_000, s.llmTimeoutMs)
        assertEquals("vosk", s.asrEngine)
        assertFalse(s.showTranscriptInDebug)
    }

    @Test
    fun `floating widget stays on when an older settings blob has no such field`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"userName":"Papa","language":"EN","protectionEnabled":false}"""
        val decoded = json.decodeFromString(SettingsState.serializer(), old)
        assertTrue(decoded.showFloatingWidget)
        assertFalse(decoded.protectionEnabled)
    }
}
