package com.kavach.app.sense

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.kavach.app.Container
import com.kavach.core.model.CallChannel
import com.kavach.core.model.CallDirection
import com.kavach.core.model.CallEnded
import com.kavach.core.model.CallStarted

/**
 * VoIP calls (WhatsApp, Instagram, Telegram, Meet...) do not trigger the phone-state broadcast.
 * Primary signal: AudioManager.getMode() == MODE_IN_COMMUNICATION, polled every 1.5 s while Protection is ON (no permission needed,
 * works for any app, icon-only call screens included). Cellular calls use MODE_IN_CALL and are handled elsewhere, so never counted here.
 * Secondary signal: call-UI text seen by accessibility (End call + Mute/Speaker/Camera), kept as a fallback.
 * Both feed one [VoipCallTracker], so exactly one CallStarted/CallEnded pair is emitted per call.
 */
class VoipCallDetector(private val c: Container) {
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = VoipCallTracker()
    private var polling = false
    private var lastVoipName: String? = null
    private var lastVoipMs = 0L
    private var knownContact: String? = null

    private val endCall = listOf("end call", "hang up", "end video call", "कॉल समाप्त", "कॉल खत्म")
    private val controls = listOf("mute", "speaker", "camera", "flip", "म्यूट", "स्पीकर")

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            try {
                val am = c.app.getSystemService(AudioManager::class.java)
                handle(tracker.onAudioPoll(am != null && am.mode == AudioManager.MODE_IN_COMMUNICATION, System.currentTimeMillis()))
            } catch (e: Exception) {
                c.live.note("VoIP audio-mode poll failed: ${e.javaClass.simpleName}")
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (polling) return
        polling = true
        handler.postDelayed(poll, POLL_MS)
    }

    /** Protection OFF. An active call is closed so the audio capture stops. */
    fun stop() {
        polling = false
        handler.removeCallbacks(poll)
        handle(tracker.forceEnd(System.currentTimeMillis()))
    }

    /** Any window event from a known VoIP app, not only call-UI text. */
    fun noteForeground(appName: String) {
        lastVoipName = appName
        lastVoipMs = System.currentTimeMillis()
    }

    fun onScreen(appName: String, texts: List<String>) {
        noteForeground(appName)
        val lower = texts.map { it.lowercase() }
        val looksLikeCallUi = lower.any { t -> endCall.any { t.contains(it) } } && lower.any { t -> controls.any { t.contains(it) } }
        if (!looksLikeCallUi) return
        val names = c.contacts.names()
        lower.firstOrNull { it in names }?.let { knownContact = it }
        handle(tracker.onCallUiSeen(System.currentTimeMillis()))
    }

    private fun label(now: Long): String {
        val n = lastVoipName
        return if (n != null && now - lastVoipMs < LABEL_FRESH_MS) n else "VoIP app"
    }

    private fun handle(t: VoipTransition?) {
        when (t) {
            null -> Unit
            is VoipTransition.Started -> {
                val label = label(t.atMs)
                val known = knownContact
                c.live.note("VoIP call (${if (t.viaAudio) "audio mode" else "call screen"}) started: $label")
                c.onCallEvents(listOf(CallStarted(t.atMs, "voip:$label", CallDirection.INCOMING, CallChannel.VOIP, isInContacts = known != null, contactName = known)))
            }
            is VoipTransition.Ended -> {
                knownContact = null
                c.live.note("VoIP call ended")
                c.onCallEvents(listOf(CallEnded(t.atMs, t.durationSec)))
            }
        }
    }

    companion object {
        const val POLL_MS = 1_500L
        const val LABEL_FRESH_MS = 30 * 60_000L
    }
}
