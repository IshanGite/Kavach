package com.kavach.app.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kavach.app.NotificationActionReceiver
import com.kavach.app.R
import com.kavach.app.ui.MainActivity
import com.kavach.app.ui.OverlayController
import com.kavach.core.orchestrator.Effect
import com.kavach.core.sense.ContactInfo
import java.util.Locale

fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** Executes the [Effect]s the core asks for. The core never touches the OS; this is the only place that does. */
class EffectExecutor(
    private val overlay: OverlayController,
    private val speaker: Speaker,
    private val sms: SmsSender,
    private val notifier: Notifier,
) {
    private val main = Handler(Looper.getMainLooper())

    fun run(engine: KavachEngine, effects: List<Effect>) {
        for (e in effects) {
            when (e) {
                is Effect.ShowNudge -> main.post { overlay.showNudge(engine, e.plan) }
                is Effect.ShowPause -> main.post { overlay.showPause(engine, e.plan) }
                is Effect.SpeakWarning -> speaker.speak(e.text, e.langTag)
                is Effect.SendGuardianSms -> e.numbers.forEach { n ->
                    val result = if (engine.dryRunSms) "DRY RUN (not sent)" else sms.send(n, e.text)
                    engine.note("Guardian SMS to $n: $result | ${e.text}")
                }
                is Effect.ExtendPause -> engine.pauseUntilMs.value = e.untilMs
                is Effect.CoercionConfirmed -> speaker.speak(e.message, engine.orchestrator.profile.language.tag)
                Effect.DismissOverlay -> main.post { overlay.dismiss() }
                is Effect.OfferCheckIn -> if (!engine.dryRunSms) notifier.checkIn(e.sawPaymentAttempt)
                Effect.GoldenHour -> if (!engine.dryRunSms) notifier.goldenHour() else main.post { overlay.showGoldenHourHint() }
            }
        }
    }
}

class Speaker(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }.also {
            it.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
        }
    }

    /** Warm the engine at app start so the first warning is not delayed by TTS initialisation. */
    fun warmUp() = Handler(Looper.getMainLooper()).post { ensure() }

    fun speak(text: String, langTag: String) {
        Handler(Looper.getMainLooper()).post {
            ensure()
            val t = tts ?: return@post
            if (!ready) { // engine still starting: retry shortly, a warning must not be dropped
                Handler(Looper.getMainLooper()).postDelayed({ if (ready) speakNow(t, text, langTag) }, 800)
            } else speakNow(t, text, langTag)
        }
    }

    private fun speakNow(t: TextToSpeech, text: String, langTag: String) {
        val loc = Locale.forLanguageTag(langTag)
        val r = t.setLanguage(loc)
        // No Hindi voice installed? Speak English rather than staying silent.
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale("en", "IN"))
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kavach-warning")
    }
}

class SmsSender(private val ctx: Context) {
    /** Returns a short status string for the log. Plain SMS: works with no internet and no backend. */
    fun send(number: String, text: String): String {
        if (!ctx.hasPermission(Manifest.permission.SEND_SMS)) return "FAILED: SEND_SMS not granted"
        return try {
            val sm = ctx.getSystemService(SmsManager::class.java)
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
            "sent"
        } catch (e: Exception) {
            "FAILED: ${e.javaClass.simpleName}"
        }
    }
}

class ContactsLookup(private val ctx: Context) {
    fun byNumber(number: String): ContactInfo? {
        if (!ctx.hasPermission(Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) ContactInfo(c.getString(0)) else null
            }
        }.getOrNull()
    }

    @Volatile private var cachedNames: Pair<Long, Set<String>>? = null

    /** Lower-cased display names, cached for a minute. Used to recognise a saved contact on a VoIP call screen. */
    fun names(): Set<String> {
        val now = System.currentTimeMillis()
        cachedNames?.let { if (now - it.first < 60_000) return it.second }
        if (!ctx.hasPermission(Manifest.permission.READ_CONTACTS)) return emptySet()
        val out = HashSet<String>()
        runCatching {
            ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.lowercase()?.let(out::add)
            }
        }
        cachedNames = now to out
        return out
    }
}

object Actions {
    /** Prefers a direct call; falls back to the dialer if CALL_PHONE is not granted. */
    fun call(ctx: Context, number: String) {
        val action = if (ctx.hasPermission(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startTel(ctx, number, action)
    }

    /**
     * Opens the dialer with [number] filled in and waits for the user to press call.
     * Used for 1930 so no CALL_PHONE permission is required and the user confirms.
     */
    fun dial(ctx: Context, number: String) = startTel(ctx, number, Intent.ACTION_DIAL)

    private fun startTel(ctx: Context, number: String, action: String) {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        ctx.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openUrl(ctx: Context, url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class Notifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Protection status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Scam alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun serviceNotification(): Notification {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Kavach is protecting you")
            .setContentText("Listens only during calls from unknown numbers. Tap Pause to stop everything.")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Pause protection", pause)
            .build()
    }

    fun checkIn(sawPayment: Boolean) {
        fun route(money: Boolean, code: Int) = PendingIntent.getActivity(
            ctx, code, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "report").putExtra(MainActivity.EXTRA_MONEY, money)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            NotificationCompat.Builder(ctx, CH_ALERT).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("That call looked suspicious. Are you okay?")
                .setContentText(if (sawPayment) "A payment was started during it. Did money leave your account?" else "Tap to prepare a complaint if it was a scam.")
                .setContentIntent(route(false, 10)).setAutoCancel(true)
                .addAction(0, "Money was sent", route(true, 11)).addAction(0, "Scam attempt", route(false, 12))
                .build(),
            NOTIF_CHECKIN,
        )
    }

    /** Money left during a high-risk session: lead with 1930, the best chance of a freeze. */
    fun goldenHour() {
        val i = PendingIntent.getActivity(
            ctx, 20, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "report").putExtra(MainActivity.EXTRA_MONEY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            NotificationCompat.Builder(ctx, CH_ALERT).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Money left your account. Call 1930 now.")
                .setContentText("Reporting within the first hour gives the best chance of freezing the funds.")
                .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(i, true).setContentIntent(i).setAutoCancel(true).build(),
            NOTIF_GOLDEN,
        )
    }

    private fun post(n: Notification, id: Int) {
        // areNotificationsEnabled() is right on every API level; POST_NOTIFICATIONS does not exist before Android 13.
        if (!androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        nm.notify(id, n)
    }

    companion object {
        const val CH_SERVICE = "kavach_service"
        const val CH_ALERT = "kavach_alert"
        const val NOTIF_SERVICE = 1
        const val NOTIF_CHECKIN = 2
        const val NOTIF_GOLDEN = 3
    }
}
