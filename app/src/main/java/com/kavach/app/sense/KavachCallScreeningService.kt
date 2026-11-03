package com.kavach.app.sense

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import com.kavach.app.container

/** Whether this app currently holds [RoleManager.ROLE_CALL_SCREENING], and the system intent that asks for it. */
object CallScreeningRole {
    fun isAvailable(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true

    fun isHeld(context: Context): Boolean {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    /** Null when the role is missing on this phone or this app already holds it. */
    fun requestIntent(context: Context): Intent? {
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }
}

/**
 * Observes the incoming number once the user grants [RoleManager.ROLE_CALL_SCREENING].
 * Always allows the call. Without the role the system does not bind this service, and [forward]
 * also refuses to touch the tracker, so call detection stays on the phone-state broadcast.
 *
 * Pattern reference: aj3423/SpamBlocker (MIT). None of its block or reject behaviour is used.
 */
class KavachCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        // Respond before any tracker or settings work so the system does not hold the ring.
        respondToCall(details, observeOnlyPolicy().toCallResponse())
        try {
            forward(details)
        } catch (_: Exception) {
            // The allow response is already delivered. A later failure must not crash this callback.
        }
    }

    private fun forward(details: Call.Details) {
        val app = applicationContext
        val c = app.container()
        val events = observeScreenedCall(
            tracker = c.callTracker,
            atMs = System.currentTimeMillis(),
            direction = details.screenedDirection(),
            handle = details.telHandle(),
            roleHeld = CallScreeningRole.isHeld(app),
            protectionEnabled = c.settings.state.value.protectionEnabled,
        ) ?: return
        c.onCallEvents(events)
    }
}

/** Copies [observeOnlyPolicy] straight through. The unit test pins every flag to false. */
private fun ScreeningResponsePolicy.toCallResponse(): CallScreeningService.CallResponse =
    CallScreeningService.CallResponse.Builder()
        .setDisallowCall(disallowCall)
        .setRejectCall(rejectCall)
        .setSilenceCall(silenceCall)
        .setSkipCallLog(skipCallLog)
        .setSkipNotification(skipNotification)
        .build()

private fun Call.Details.screenedDirection(): ScreenedCallDirection = when (callDirection) {
    Call.Details.DIRECTION_INCOMING -> ScreenedCallDirection.INCOMING
    Call.Details.DIRECTION_OUTGOING -> ScreenedCallDirection.OUTGOING
    else -> ScreenedCallDirection.UNKNOWN
}

/** `tel:` handles become a URI string the pure parser understands. Anything else is left for that parser to ignore. */
private fun Call.Details.telHandle(): String? {
    val uri = handle ?: return null
    val scheme = uri.scheme
    return if (scheme != null && scheme.equals("tel", ignoreCase = true)) {
        "tel:${uri.schemeSpecificPart ?: ""}"
    } else {
        uri.toString()
    }
}
