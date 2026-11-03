package com.kavach.app.sense

import android.os.Handler
import android.os.Looper
import com.kavach.app.Container
import com.kavach.core.model.CallChannel
import com.kavach.core.model.CallDirection
import com.kavach.core.model.CallEnded
import com.kavach.core.model.CallStarted

/**
 * WhatsApp / Skype / Telegram calls do not go through the phone-state broadcast, and digital-arrest scammers prefer them.
 * Heuristic: the accessibility service sees a call screen (End call + Mute/Speaker/Camera) of a VoIP app. A saved contact's name
 * on that screen means a known caller. The call is considered live while the app still has an ongoing-call notification.
 *
 * FRAGILE BY NATURE: UI text varies by app version and language. Treat as "needs tuning on the demo phone" (see HANDOVER.md).
 */
class VoipCallDetector(private val c: Container) {
    private val handler = Handler(Looper.getMainLooper())
    private var activePkg: String? = null
    private var startedAt = 0L
    private var lastSeenMs = 0L

    private val endCall = listOf("end call", "hang up", "end video call", "कॉल समाप्त", "कॉल खत्म")
    private val controls = listOf("mute", "speaker", "camera", "flip", "म्यूट", "स्पीकर")

    fun onScreen(pkg: String, appName: String, texts: List<String>) {
        val lower = texts.map { it.lowercase() }
        val looksLikeCallUi = lower.any { t -> endCall.any { t.contains(it) } } && lower.any { t -> controls.any { t.contains(it) } }
        if (!looksLikeCallUi) return
        lastSeenMs = System.currentTimeMillis()
        if (activePkg != null) return
        val names = c.contacts.names()
        val known = lower.firstOrNull { it in names }
        activePkg = pkg
        startedAt = lastSeenMs
        c.live.note("VoIP call detected in $appName (${if (known != null) "known contact: $known" else "unknown caller"})")
        c.onCallEvents(
            listOf(CallStarted(startedAt, "voip:$appName", CallDirection.INCOMING, CallChannel.VOIP, isInContacts = known != null, contactName = known)),
        )
        scheduleCheck()
    }

    private fun scheduleCheck() { handler.postDelayed({ check() }, 15_000) }

    private fun check() {
        val pkg = activePkg ?: return
        val listener = KavachNotificationListener.instance
        // With notification access we can ask directly; without it, assume the call continues for 2 minutes after its UI was last seen.
        val live = if (listener != null) listener.hasOngoingCall(pkg) || System.currentTimeMillis() - lastSeenMs < 20_000
        else System.currentTimeMillis() - lastSeenMs < 120_000
        if (live) { scheduleCheck(); return }
        val now = System.currentTimeMillis()
        activePkg = null
        c.live.note("VoIP call ended")
        c.onCallEvents(listOf(CallEnded(now, (now - startedAt) / 1000)))
    }
}
