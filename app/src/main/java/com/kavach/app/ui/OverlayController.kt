package com.kavach.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kavach.app.engine.Actions
import com.kavach.app.engine.KavachEngine
import com.kavach.core.intervention.InterventionPlan
import com.kavach.core.model.QuestionsAnswered
import com.kavach.core.model.UserProceeded

/** Gives a ComposeView living in a WindowManager window the lifecycle owners Compose insists on. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

/**
 * Draws the nudge banner and the full-screen pause on top of the payment app.
 *
 * Preferred window type is TYPE_ACCESSIBILITY_OVERLAY (from our AccessibilityService: needs no extra permission and is the most
 * reliable on OEM ROMs); falls back to TYPE_APPLICATION_OVERLAY, which needs "Display over other apps".
 * Always called on the main thread (EffectExecutor posts).
 */
class OverlayController(private val app: Context) {
    @Volatile var accessibilityContext: Context? = null

    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var windowManager: WindowManager? = null

    fun canDraw(): Boolean = accessibilityContext != null || Settings.canDrawOverlays(app)

    fun isShowing() = view != null

    fun showNudge(engine: KavachEngine, plan: InterventionPlan) = show(engine, fullScreen = false) {
        NudgeBanner(
            plan,
            onCall = { plan.callTrusted?.let { c -> Actions.call(app, c.number) } },
            onDismiss = { engine.emit(UserProceeded(engine.now())); dismiss() },
        )
    }

    fun showPause(engine: KavachEngine, plan: InterventionPlan) = show(engine, fullScreen = true) {
        PauseScreen(
            plan = plan,
            pauseUntilMs = engine.pauseUntilMs,
            now = engine::now,
            onCall = { plan.callTrusted?.let { c -> Actions.call(app, c.number) } },
            onCallHelpline = { plan.callHelpline?.let { n -> Actions.dial(app, n) } },
            onAnswered = { yes -> engine.emit(QuestionsAnswered(engine.now(), yes)) },
            onProceed = { engine.emit(UserProceeded(engine.now())); dismiss() },
        )
    }

    /** Sim-mode only hint: in a real session the golden-hour notification takes over. */
    fun showGoldenHourHint() {
        Toast.makeText(app, "Golden hour: the app would now lead with CALL 1930", Toast.LENGTH_LONG).show()
    }

    fun dismiss() {
        val v = view ?: return
        runCatching { windowManager?.removeViewImmediate(v) }
        owner?.destroy()
        view = null; owner = null
    }

    private fun show(engine: KavachEngine, fullScreen: Boolean, content: @Composable () -> Unit) {
        dismiss()
        if (!canDraw()) {
            engine.note("OVERLAY BLOCKED: grant 'Display over other apps' or enable the accessibility service (Setup screen)")
            return
        }
        val a11y = accessibilityContext
        val ctx = a11y ?: app
        val wm = ctx.getSystemService(WindowManager::class.java)
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val o = OverlayOwner()
        val cv = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { KavachTheme { content() } }
        }
        val flags = if (fullScreen)
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        else
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (fullScreen) Gravity.CENTER else Gravity.TOP
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(cv, lp)
            view = cv; owner = o; windowManager = wm
        } catch (e: Exception) {
            o.destroy()
            engine.note("OVERLAY FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
