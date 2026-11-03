package com.kavach.app

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.kavach.app.audio.AsrEngine
import com.kavach.app.audio.AudioCaptureController
import com.kavach.app.audio.CompanionServer
import com.kavach.app.audio.NoopAsr
import com.kavach.app.audio.SherpaWhisperAsr
import com.kavach.app.audio.VoskAsr
import com.kavach.app.audio.normalizeAsrEngine
import com.kavach.app.audio.preferModelId
import com.kavach.app.engine.*
import com.kavach.app.sense.VoipCallDetector
import com.kavach.app.ui.OverlayController
import com.kavach.app.ui.WidgetController
import com.kavach.core.baseline.PastPayment
import com.kavach.core.config.AppConfig
import com.kavach.core.model.CallEnded
import com.kavach.core.model.CallStarted
import com.kavach.core.model.KavachEvent
import com.kavach.core.sense.CallStateTracker
import com.kavach.core.sim.ScenarioLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread

/** Last screen the accessibility service read, for the Debug screen. Paste a dump into ScreenParserTest to tune a UPI app. */
data class ScreenDump(val pkg: String, val atMs: Long, val texts: List<String>, val summary: String)

/** Hand-rolled service locator: one object, no DI framework to debug at 3 am. */
class Container(val app: Application) {
    val settings = Settings(app)
    val appConfig: AppConfig = AppConfig.loadDefault()
    val contacts = ContactsLookup(app)
    val overlay = OverlayController(app)
    val speaker = Speaker(app)
    val notifier = Notifier(app)
    private val sms = SmsSender(app)
    val executor = EffectExecutor(overlay, speaker, sms, notifier)
    val baseline = PrefsBaseline(app)

    /** Fed by real sensors. */
    val live = KavachEngine("live", settings, baseline, executor).also { it.start() }

    val callTracker = CallStateTracker({ contacts.byNumber(it) })
    val voip = VoipCallDetector(this)
    val screenDump = MutableStateFlow<ScreenDump?>(null)
    val audioLevelDb = MutableStateFlow(-90f)

    /**
     * On-device ASR. Vosk is the default. Whisper loads when Settings asks for it and the model files
     * are present; otherwise this falls back to Vosk, then [NoopAsr]. The load runs off the main thread.
     * Home collects [asrState]. Whisper's detected language is published on [whisperStatus] because the
     * engine object itself does not change when a window reports a language.
     */
    val asrState = MutableStateFlow<AsrEngine>(
        if (normalizeAsrEngine(settings.state.value.asrEngine) == "whisper") SherpaWhisperAsr.LOADING else VoskAsr.LOADING,
    )
    val whisperStatus = MutableStateFlow("")
    var asr: AsrEngine
        get() = asrState.value
        set(value) {
            asrState.value = value
            if (value is SherpaWhisperAsr) whisperStatus.value = value.name
        }

    private val asrLoadLock = Any()
    private var whisperEngine: SherpaWhisperAsr? = null
    private var voskEngine: AsrEngine? = null
    val audio = AudioCaptureController(app, live, settings, audioLevelDb) { asr }
    val companion = CompanionServer(settings, live)

    /** Latest throw-away engine from the Simulate screen. The widget follows this while it is non-null. */
    @Volatile var sim: KavachEngine? = null

    /** Shield bubble. Created on first use so a process that never turns protection on does not add a window. */
    val widget: WidgetController by lazy {
        WidgetController(app, overlay, settings, live = { live }, sim = { sim })
    }

    fun newSimEngine(simClock: () -> Long): KavachEngine {
        sim?.stop()
        val e = KavachEngine(
            "sim", settings, PrefsBaselineView(), executor, dryRunSms = true, clockFn = simClock, tickEveryMs = Long.MAX_VALUE / 4,
            profileOf = { s -> ScenarioLoader.defaultProfile().copy(language = s.lang(), userName = s.userName.ifBlank { "Papa" }) },
        )
        e.start()
        sim = e
        return e
    }

    /** The demo user's usual payments, independent of whatever the live baseline holds. */
    private fun PrefsBaselineView() = com.kavach.core.baseline.InMemoryBaseline(
        ScenarioLoader.defaultBaseline().flatMap { b -> (0 until b.count).map { PastPayment(b.payee, b.amount, 0L) } },
    )

    fun seedDemoBaseline() {
        val t0 = System.currentTimeMillis() - 3 * 86_400_000L
        baseline.replaceAll(ScenarioLoader.defaultBaseline().flatMap { b -> (0 until b.count).map { PastPayment(b.payee, b.amount, t0 - it * 3_600_000L) } })
        live.note("Baseline seeded with demo payment history")
    }

    /** All real call events (cellular or VoIP) go through here: engine first, then audio capture on/off. */
    fun onCallEvents(events: List<KavachEvent>) {
        for (e in events) {
            live.emit(e)
            when {
                e is CallStarted && !e.isInContacts -> audio.start()
                e is CallEnded -> audio.stop()
            }
        }
    }

    /** The engine whose session the report screen should describe: whichever saw the most recent activity. */
    fun reportEngine(): KavachEngine = sim?.takeIf { it.lastActivityMs > live.lastActivityMs } ?: live

    /**
     * Reload the engine selected in Settings. Safe to call more than once. Model reads stay off the main
     * thread. A Whisper engine that is no longer selected is released; Vosk stays cached.
     */
    fun reloadAsr() {
        thread(name = "kavach-asr-load", isDaemon = true) {
            synchronized(asrLoadLock) {
                val (engine, detail) = try {
                    selectAsr()
                } catch (t: Throwable) {
                    NoopAsr to "ASR load failed: ${t.javaClass.simpleName}"
                }
                val previous = asr
                asr = engine
                if (previous is SherpaWhisperAsr && previous !== engine) {
                    if (whisperEngine === previous) whisperEngine = null
                    previous.release()
                }
                live.note(detail)
            }
        }
    }

    private fun selectAsr(): Pair<AsrEngine, String> {
        if (normalizeAsrEngine(settings.state.value.asrEngine) == "whisper") {
            whisperEngine?.let { return it to "ASR ready: ${it.name}" }
            val (engine, detail) = SherpaWhisperAsr.tryLoad(
                app,
                note = { live.note(it) },
                onLabel = { whisperStatus.value = it },
            )
            if (engine != null) {
                whisperEngine = engine
                return engine to detail
            }
            val (vosk, voskNote) = loadVosk()
            return (vosk ?: NoopAsr) to "$detail; fell back to $voskNote"
        }
        val (vosk, voskNote) = loadVosk()
        return (vosk ?: NoopAsr) to voskNote
    }

    private fun loadVosk(): Pair<AsrEngine?, String> {
        voskEngine?.let { return it to "ASR ready: ${it.name}" }
        val (engine, detail) = VoskAsr.tryLoad(app) { preferModelId(settings.state.value.language) }
        if (engine != null) voskEngine = engine
        return engine to detail
    }

    init {
        // Model reads are tens of megabytes and must not run on the main thread.
        reloadAsr()
    }
}

class KavachApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        container.notifier.createChannels()
        container.speaker.warmUp()
        if (container.settings.state.value.protectionEnabled) KavachService.start(this) // restart after process death or reboot
    }
}

fun Context.container(): Container = (applicationContext as KavachApp).container

/**
 * The foreground service keeps the process alive and owns the microphone capture and the optional companion listener.
 * Android 14 only allows the microphone foreground type when RECORD_AUDIO is granted AND the service starts while the app is
 * visible, so this service is started from the UI (toggle on the Home screen), not from a background receiver.
 */
class KavachService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = container()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (hasPermission(Manifest.permission.RECORD_AUDIO)) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        try {
            ServiceCompat.startForeground(this, Notifier.NOTIF_SERVICE, c.notifier.serviceNotification(), types)
        } catch (e: Exception) {
            // e.g. mic type refused because the app was in the background. Retry without the microphone type.
            c.live.note("Foreground start with mic refused (${e.javaClass.simpleName}); running without mic")
            ServiceCompat.startForeground(this, Notifier.NOTIF_SERVICE, c.notifier.serviceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        if (c.settings.state.value.companionEnabled) c.companion.start()
        c.widget.start()
        c.live.warmUpModel()
        c.live.note("Protection ON")
        return START_STICKY
    }

    override fun onDestroy() {
        val c = container()
        c.audio.stop(); c.companion.stop(); c.widget.stop(); c.overlay.dismiss()
        c.live.note("Protection OFF")
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, KavachService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KavachService::class.java))
        }
    }
}

/** "Pause protection" action on the persistent notification: the spec's one switch that pauses everything. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PAUSE) {
            context.container().settings.update { it.copy(protectionEnabled = false) }
            KavachService.stop(context)
        }
    }

    companion object { const val ACTION_PAUSE = "com.kavach.app.PAUSE" }
}
