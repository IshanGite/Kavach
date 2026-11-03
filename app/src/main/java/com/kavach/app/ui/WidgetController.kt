package com.kavach.app.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kavach.app.engine.Actions
import com.kavach.app.engine.KavachEngine
import com.kavach.app.engine.Settings
import com.kavach.app.engine.WidgetSurface
import com.kavach.core.config.AppConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import kotlin.math.min

/**
 * The always-on shield. A small window, not a full-screen one: [WidgetWindowPolicy.touchFlags] are
 * NOT_FOCUSABLE and NOT_TOUCH_MODAL, and the window is only the bubble or the card.
 *
 * Window type matches [OverlayController]: accessibility overlay when that service is connected,
 * otherwise the application overlay (needs "Display over other apps").
 */
/** The widget follows the throw-away sim engine only while a scenario is really playing; otherwise real calls. */
internal fun <T : Any> selectWidgetSource(simPlaying: Boolean, sim: T?, live: T): T =
    if (simPlaying && sim != null) sim else live

/** After a failed addView, wait this long before building another ComposeView. */
internal const val WIDGET_RETRY_BACKOFF_MS = 5_000L

internal fun widgetRetryAllowed(nowMs: Long, failedUntilMs: Long): Boolean = nowMs >= failedUntilMs

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetController(
    private val app: Context,
    private val overlay: OverlayController,
    private val settings: Settings,
    private val live: () -> KavachEngine,
    private val sim: () -> KavachEngine?,
    private val simPlaying: () -> Boolean = { SimPlayer.playing },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shown = mutableStateOf(WidgetMapper.project(WidgetSurface()))

    private var job: Job? = null
    private var running = false
    private var latch = WidgetUiLatch()
    private var familyNumber: String? = null
    private var host: ComposeView? = null
    private var owner: WidgetOwner? = null
    private var windowManager: WindowManager? = null
    private var boundA11y: Boolean? = null
    private var posX = 0
    private var posY = 0
    private var dockRight = true
    private var placed = false
    private var dragging = false
    private var notedBlock = false
    private var notedFailure = false
    private var pauseCovering = false
    private var failedUntilMs = 0L
    private var paymentForeground = false
    private var family: com.kavach.core.intervention.TrustedContact? = null
    private val appConfig: AppConfig by lazy { AppConfig.loadDefault() }
    private val activeEngine = MutableStateFlow<KavachEngine?>(null)

    fun start() {
        if (running) return
        running = true
        val existing = active().surface.value.scamNotices.map { it.id }.toSet()
        latch = WidgetUiLatch(dismissedAlertIds = existing)
        overlay.onAccessibilityContextChanged = { scope.launch { if (running) retarget() } }
        activeEngine.value = active()
        job = scope.launch {
            // Change-driven: a new surface or a settings edit redraws at once.
            launch { activeEngine.filterNotNull().flatMapLatest { it.surface }.collect { render() } }
            launch {
                settings.state.collect { st ->
                    val p = st.toProfile()
                    family = p.familyContacts.firstOrNull() ?: p.guardians.firstOrNull()
                    render()
                }
            }
            // Light tick: the call timer, the sim/live switch, and which app is in front.
            while (isActive && running) {
                activeEngine.value = active()
                val pay = detectPaymentForeground()
                if (pay != paymentForeground) {
                    paymentForeground = pay
                    if (!pay && latch.paymentPeek) latch = latch.copy(paymentPeek = false)
                }
                render()
                delay(500)
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        overlay.onAccessibilityContextChanged = null
        removeWindow()
        latch = WidgetUiLatch()
    }

    private fun active(): KavachEngine = selectWidgetSource(simPlaying(), sim(), live())

    private fun detectPaymentForeground(): Boolean {
        val svc = overlay.accessibilityContext as? AccessibilityService ?: return false
        val pkg = runCatching {
            val root = svc.rootInActiveWindow
            val name = root?.packageName?.toString()
            @Suppress("DEPRECATION") root?.recycle()
            name
        }.getOrNull()
        return appConfig.paymentAppName(pkg) != null
    }

    private fun retarget() {
        removeWindow()
        render()
    }

    private fun render() {
        if (!running) return
        try {
            val st = settings.state.value
            if (!WidgetMapper.shouldShow(st.protectionEnabled, st.showFloatingWidget) || !overlay.canDraw()) {
                if (!overlay.canDraw() && st.protectionEnabled && st.showFloatingWidget) noteBlocked()
                removeWindow()
                return
            }
            notedBlock = false
            val engine = active()
            val surface = engine.surface.value
            val family = family
            familyNumber = family?.number
            pauseCovering = surface.tierLevel >= 2 && overlay.isShowing()
            shown.value = WidgetMapper.project(
                surface = surface,
                latch = latch,
                nowMs = maxOf(surface.atMs, engine.now()),
                lang = st.lang(),
                familyName = family?.name,
                fullScreenPauseShowing = pauseCovering,
                paymentAppForeground = paymentForeground,
            )
            ensureWindow()
            applyLayout()
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Exception) {
            if (!notedFailure) {
                notedFailure = true
                live().note("Widget failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun safely(what: String, block: () -> Unit) {
        runCatching(block).onFailure { live().note("Widget could not $what: ${it.javaClass.simpleName}") }
    }

    private fun noteBlocked() {
        if (notedBlock) return
        notedBlock = true
        live().note("WIDGET BLOCKED: grant 'Display over other apps' or enable the accessibility service")
    }

    private fun mutate(block: (WidgetUiLatch, WidgetSurface) -> WidgetUiLatch) {
        latch = block(latch, active().surface.value)
        render()
    }

    private fun ensureWindow() {
        val useA11y = overlay.accessibilityContext != null
        if (host != null && boundA11y == useA11y) return
        if (host == null && !widgetRetryAllowed(SystemClock.elapsedRealtime(), failedUntilMs)) return
        removeWindow()
        val a11y = overlay.accessibilityContext
        val ctx = a11y ?: app
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val type = if (a11y != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val o = WidgetOwner()
        val compose = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            isFocusable = false
            isFocusableInTouchMode = false
            setContent {
                KavachTheme {
                    val view = shown.value
                    GuardianWidget(
                        view = view,
                        onDrag = ::onDrag,
                        onDragEnd = ::onDragEnd,
                        onBubbleTap = { mutate { l, s -> WidgetMapper.bubbleTap(l, s, pauseCovering, paymentForeground) } },
                        onCollapse = { mutate { l, s -> WidgetMapper.collapse(l, s) } },
                        onDismiss = { mutate { l, s -> WidgetMapper.dismiss(l, s) } },
                        onCallHelpline = { safely("dial helpline") { Actions.dial(app, WidgetWindowPolicy.HELPLINE) } },
                        onCallFamily = { familyNumber?.let { n -> safely("call family") { Actions.call(app, n) } } },
                        onOpenHistory = { mutate { l, s -> WidgetMapper.openHistory(l, s) } },
                        onCloseHistory = { mutate { l, _ -> WidgetMapper.closeHistory(l) } },
                    )
                }
            }
        }
        if (!placed) placeInitial()
        val (w, h) = windowPx(shown.value.card)
        val lp = WindowManager.LayoutParams(
            w, h, type, layoutFlags(), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(compose, lp)
            host = compose
            owner = o
            windowManager = wm
            boundA11y = useA11y
        } catch (e: Exception) {
            o.destroy()
            failedUntilMs = SystemClock.elapsedRealtime() + WIDGET_RETRY_BACKOFF_MS
            if (!notedFailure) {
                notedFailure = true
                live().note("WIDGET FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun layoutFlags(): Int =
        WidgetWindowPolicy.touchFlags() or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun removeWindow() {
        val v = host ?: return
        runCatching { windowManager?.removeViewImmediate(v) }
        owner?.destroy()
        host = null
        owner = null
        windowManager = null
        boundA11y = null
    }

    private fun onDrag(dx: Float, dy: Float) {
        dragging = true
        if (!placed) placeInitial()
        val (sw, sh) = screen()
        val (w, h) = currentSize()
        posX = (posX + dx.toInt()).coerceIn(0, (sw - w).coerceAtLeast(0))
        posY = (posY + dy.toInt()).coerceIn(0, (sh - h).coerceAtLeast(0))
        writeLayout(w, h)
    }

    private fun onDragEnd() {
        dragging = false
        val (sw, _) = screen()
        val (w, _) = currentSize()
        dockRight = posX + w / 2 >= sw / 2
        snap()
    }

    private fun placeInitial() {
        val (sw, sh) = screen()
        val (w, _) = currentSize()
        val margin = dp(8)
        posX = (sw - w - margin).coerceAtLeast(margin)
        posY = (sh * 0.35f).toInt()
        dockRight = true
        placed = true
    }

    private fun applyLayout() {
        if (dragging) return
        if (!placed) placeInitial()
        snap()
    }

    private fun snap() {
        val (sw, sh) = screen()
        val (w, h) = currentSize()
        val margin = dp(8)
        posX = if (dockRight) (sw - w - margin).coerceAtLeast(margin) else margin
        posY = posY.coerceIn(margin, (sh - h - margin).coerceAtLeast(margin))
        placed = true
        writeLayout(w, h)
    }

    private fun writeLayout(w: Int, h: Int) {
        val v = host ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val flags = layoutFlags()
        if (lp.width == w && lp.height == h && lp.x == posX && lp.y == posY && lp.flags == flags) return
        lp.width = w
        lp.height = h
        lp.x = posX
        lp.y = posY
        lp.flags = flags
        runCatching { windowManager?.updateViewLayout(v, lp) }
    }

    private fun currentSize(): Pair<Int, Int> = windowPx(shown.value.card)

    /** Exact pixel size of the bubble or the card. Never MATCH_PARENT, so the rest of the screen stays tappable. */
    private fun windowPx(card: WidgetCard): Pair<Int, Int> {
        val (wDp, hDp) = WidgetWindowPolicy.layoutSizeDp(card)
        val w = dp(wDp).coerceAtLeast(1)
        var h = dp(hDp).coerceAtLeast(1)
        if (card != WidgetCard.BUBBLE) {
            val cap = (screen().second * 0.62f).toInt()
            val floor = dp(200)
            if (cap > floor) h = min(h, cap)
        }
        return w to h
    }

    private fun screen(): Pair<Int, Int> {
        val metrics = app.resources.displayMetrics
        val fallback = metrics.widthPixels to metrics.heightPixels
        val wm = windowManager ?: return fallback
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else fallback
        }.getOrDefault(fallback)
    }

    private fun dp(value: Int) = (value * app.resources.displayMetrics.density).toInt()
}

private class WidgetOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
