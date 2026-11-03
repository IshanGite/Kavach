package com.kavach.app.engine

import android.content.Context
import com.kavach.core.baseline.InMemoryBaseline
import com.kavach.core.baseline.PastPayment
import com.kavach.core.baseline.PaymentBaseline
import com.kavach.core.intervention.Lang
import com.kavach.core.intervention.TrustedContact
import com.kavach.core.intervention.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ContactDto(val name: String = "", val number: String = "", val relation: String = "")

/** Everything the user can configure. One immutable snapshot, persisted as a single JSON blob in private prefs. */
@Serializable
data class SettingsState(
    val userName: String = "Papa",
    /** "EN" or "HI" */
    val language: String = "EN",
    val guardians: List<ContactDto> = emptyList(),
    val family: List<ContactDto> = emptyList(),
    /** Only a flag: the safe word itself is never stored on the phone. */
    val safeWordSet: Boolean = false,
    val cloudEnabled: Boolean = false,
    val cloudApiKey: String = "",
    val ollamaUrl: String = "http://127.0.0.1:11434",
    val ollamaModel: String = "gemma3:1b",
    val llmTimeoutMs: Long = 8_000,
    /** One few-shot instead of three: faster on a phone CPU. */
    val compactPrompt: Boolean = true,
    val speakNudges: Boolean = false,
    val protectionEnabled: Boolean = false,
    /** Floating shield over other apps. On unless the user turns it off. The bubble never takes taps outside itself. */
    val showFloatingWidget: Boolean = true,
    /** VOICE_RECOGNITION | MIC | CAMCORDER | UNPROCESSED. Set from the Day-0 audio test result. */
    val audioSource: String = "VOICE_RECOGNITION",
    /** "vosk" (default) or "whisper". Missing Whisper files fall back to Vosk, then no ASR. */
    val asrEngine: String = "vosk",
    /** Whisper decode language: "auto" (hi/en/mr), "hi", "en" or "mr". */
    val speechLanguage: String = "auto",
    /**
     * Diagnostic. When on, the Debug log gets one note per accepted ASR window with the transcript text,
     * language and mic level. In memory only, never persisted by the log, never sent anywhere. Off by default.
     */
    val showTranscriptInDebug: Boolean = false,
    val companionEnabled: Boolean = false,
    val companionToken: String = "",
) {
    fun lang() = if (language == "HI") Lang.HI else Lang.EN

    fun toProfile() = UserProfile(
        userName = userName.ifBlank { "User" },
        language = lang(),
        guardians = guardians.filter { it.number.isNotBlank() }.map { TrustedContact(it.name.ifBlank { "Guardian" }, it.number, it.relation) },
        familyContacts = family.filter { it.number.isNotBlank() }.map { TrustedContact(it.name.ifBlank { "Family" }, it.number, it.relation) },
        familySafeWordSet = safeWordSet,
    )
}

class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("kavach_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state

    private fun load(): SettingsState =
        prefs.getString("settings", null)?.let { runCatching { json.decodeFromString(SettingsState.serializer(), it) }.getOrNull() } ?: SettingsState()

    fun update(f: (SettingsState) -> SettingsState) {
        synchronized(this) {
            val next = f(_state.value)
            _state.value = next
            prefs.edit().putString("settings", json.encodeToString(SettingsState.serializer(), next)).apply()
        }
    }
}

/** Payment history that survives restarts. This is what keeps everyday payments friction-free. */
class PrefsBaseline(ctx: Context) : PaymentBaseline {
    @Serializable
    private data class Row(val payee: String, val amount: Double, val atMs: Long)

    private val prefs = ctx.getSharedPreferences("kavach_baseline", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val inner = InMemoryBaseline(load())

    private fun load(): List<PastPayment> =
        prefs.getString("rows", null)?.let { s ->
            runCatching { json.decodeFromString(ListSerializer(Row.serializer()), s).map { PastPayment(it.payee, it.amount, it.atMs) } }.getOrNull()
        } ?: emptyList()

    private fun persist() {
        val rows = inner.snapshot().takeLast(500).map { Row(it.payee, it.amountRupees, it.atMs) }
        prefs.edit().putString("rows", json.encodeToString(ListSerializer(Row.serializer()), rows)).apply()
    }

    override fun historySize() = inner.historySize()
    override fun medianAmount() = inner.medianAmount()
    override fun paymentCount(payee: String?) = inner.paymentCount(payee)

    @Synchronized
    override fun record(payment: PastPayment) { inner.record(payment); persist() }

    @Synchronized
    fun replaceAll(list: List<PastPayment>) { inner.replaceAll(list); persist() }
}
