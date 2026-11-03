package com.kavach.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.kavach.app.engine.hasPermission
import com.kavach.app.sense.CallScreeningRole
import com.kavach.app.sense.KavachAccessibilityService

data class Check(val label: String, val ok: Boolean, val why: String)

/** One place that says whether this phone is actually ready to protect. Drives the Home checklist and the Setup screen. */
object Health {
    fun accessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, KavachAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun notificationAccess(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun batteryExempt(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun checks(ctx: Context): List<Check> = listOf(
        Check("Phone state + call log", ctx.hasPermission(Manifest.permission.READ_PHONE_STATE) && ctx.hasPermission(Manifest.permission.READ_CALL_LOG), "Know when a call starts and who is calling"),
        Check("Caller ID (call screening role)", CallScreeningRole.isHeld(ctx), "Incoming number on Android 10+, and whether they are a saved contact. The call always rings"),
        Check("Contacts", ctx.hasPermission(Manifest.permission.READ_CONTACTS), "Saved contacts are never analysed or recorded"),
        Check("Microphone", ctx.hasPermission(Manifest.permission.RECORD_AUDIO), "Listen on speakerphone during calls from unknown numbers only"),
        Check("SMS", ctx.hasPermission(Manifest.permission.RECEIVE_SMS) && ctx.hasPermission(Manifest.permission.SEND_SMS), "Spot scam texts, send guardian alerts, read the guardian's HOLD"),
        Check("Notifications", NotificationManagerCompat.from(ctx).areNotificationsEnabled(), "Show the status and post-call check-in"),
        Check("Accessibility service", accessibilityEnabled(ctx), "See which payment app is open, amount and payee. Also draws the pause screen"),
        Check("Notification access", notificationAccess(ctx), "Read WhatsApp/UPI notifications and detect remote-access apps"),
        Check("Display over other apps", Settings.canDrawOverlays(ctx) || accessibilityEnabled(ctx), "Fallback for drawing the pause screen"),
        Check("Battery optimisation off", batteryExempt(ctx), "Stops Android killing the guardian mid-call"),
    )
}
