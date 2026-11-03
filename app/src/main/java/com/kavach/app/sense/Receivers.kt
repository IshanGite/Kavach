package com.kavach.app.sense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.TelephonyManager
import com.kavach.app.container
import com.kavach.core.intervention.GuardianLink
import com.kavach.core.model.GuardianHold
import com.kavach.core.model.MessageReceived
import com.kavach.core.sense.PhoneState

/** Cellular call state. A manifest receiver, so it fires even if the UI is not open. Needs READ_PHONE_STATE + READ_CALL_LOG for the number. */
class PhoneStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val c = context.container()
        if (!c.settings.state.value.protectionEnabled) return
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> PhoneState.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> PhoneState.OFFHOOK
            else -> PhoneState.IDLE
        }
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        c.onCallEvents(c.callTracker.onPhoneState(System.currentTimeMillis(), state, number))
    }
}

/**
 * Incoming SMS: gives the exact sender address (a notification only gives a display name), which is what lets a guardian's
 * "HOLD" reply be authenticated against the registered guardian numbers.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val c = context.container()
        if (!c.settings.state.value.protectionEnabled) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val now = System.currentTimeMillis()
        parts.groupBy { it.originatingAddress ?: "unknown" }.forEach { (from, msgs) ->
            val body = msgs.joinToString("") { it.messageBody.orEmpty() }
            if (GuardianLink.isHold(body)) c.live.emit(GuardianHold(now, from)) // authenticated inside the orchestrator
            else c.live.emit(MessageReceived(now, from, body))
        }
    }
}
