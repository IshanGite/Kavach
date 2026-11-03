package com.kavach.app.sense

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kavach.app.Container
import com.kavach.app.ScreenDump
import com.kavach.app.container
import com.kavach.core.model.PaymentDismissed
import com.kavach.core.model.PaymentIntent
import com.kavach.core.model.RemoteAccessDetected
import com.kavach.core.model.RemoteAccessEnded
import com.kavach.core.sense.ParsedScreen
import com.kavach.core.sense.ScreenParser

/**
 * The most important sensor: it is what lets Kavach act BEFORE the PIN screen.
 *  - which payment app is in front, and the amount / payee / PIN screen on it (via [ScreenParser])
 *  - when the user leaves the payment app (closes the overlay and the payment intent)
 *  - remote-access apps (AnyDesk, TeamViewer...) coming to the front
 *  - VoIP call screens (see [VoipCallDetector])
 * It also hosts the overlay window (TYPE_ACCESSIBILITY_OVERLAY), which needs no "display over other apps" permission.
 * Event handling is throttled and only parses payment apps' windows.
 */
class KavachAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var c: Container

    private var lastPaymentPkg: String? = null
    private var lastEmitted: PaymentIntent? = null
    private var lastParseMs = 0L
    private var dismissRunnable: Runnable? = null
    private var remoteExpiry: Runnable? = null
    private var remoteApp: String? = null

    override fun onServiceConnected() {
        c = container()
        c.overlay.accessibilityContext = this
        c.live.note("Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (::c.isInitialized) c.overlay.accessibilityContext = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::c.isInitialized || !c.settings.state.value.protectionEnabled) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        val payName = c.appConfig.paymentAppName(pkg)
        val remoteName = c.appConfig.remoteAccessName(pkg)
        val voipName = c.appConfig.voipName(pkg)

        if (remoteName != null && isWindowChange) onRemoteAccess(remoteName)

        when {
            payName != null -> onPaymentWindow(pkg, payName)
            voipName != null -> {
                val now = System.currentTimeMillis()
                if (now - lastParseMs > 800) { lastParseMs = now; c.voip.onScreen(pkg, voipName, collectTexts(rootInActiveWindow)) }
                if (isWindowChange) onLeftPaymentApp(pkg)
            }
            isWindowChange -> onLeftPaymentApp(pkg)
        }
    }

    private fun onRemoteAccess(app: String) {
        val now = System.currentTimeMillis()
        if (remoteApp != app) {
            remoteApp = app
            c.live.emit(RemoteAccessDetected(now, app))
        }
        // We cannot see a screen share end, so the flag lapses after 15 minutes unless the app comes to the front again.
        remoteExpiry?.let(handler::removeCallbacks)
        remoteExpiry = Runnable { c.live.emit(RemoteAccessEnded(System.currentTimeMillis(), app)); remoteApp = null }.also { handler.postDelayed(it, 15 * 60_000L) }
    }

    private fun onPaymentWindow(pkg: String, appName: String) {
        val now = System.currentTimeMillis()
        dismissRunnable?.let(handler::removeCallbacks); dismissRunnable = null
        if (now - lastParseMs < 250) return // content-changed events fire constantly
        lastParseMs = now

        val texts = collectTexts(rootInActiveWindow)
        val parsed = ScreenParser.parse(texts, c.appConfig.pinScreenHints)
        lastPaymentPkg = pkg
        publishDump(pkg, texts, parsed)

        val intent = PaymentIntent(now, appName, parsed.stage, parsed.amountRupees, parsed.payee)
        val prev = lastEmitted
        // Emit only when something meaningful changed.
        if (prev == null || prev.app != appName || prev.stage != intent.stage || prev.amountRupees != intent.amountRupees || prev.payee != intent.payee) {
            lastEmitted = intent
            c.live.emit(intent)
        }
    }

    private fun onLeftPaymentApp(pkg: String) {
        if (lastPaymentPkg == null) return
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return // shade or IME are not "leaving"
        if (dismissRunnable != null) return
        // Debounced: UPI apps hand off between their own windows and the bank's PIN UI.
        dismissRunnable = Runnable {
            lastPaymentPkg = null; lastEmitted = null; dismissRunnable = null
            c.live.emit(PaymentDismissed(System.currentTimeMillis()))
        }.also { handler.postDelayed(it, 2_000) }
    }

    private fun publishDump(pkg: String, texts: List<String>, p: ParsedScreen) {
        c.screenDump.value = ScreenDump(pkg, System.currentTimeMillis(), texts.take(80), "stage=${p.stage} amount=${p.amountRupees} payee=${p.payee}")
    }

    /** Visible text and content descriptions of the window, bounded in size and depth. */
    private fun collectTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val out = ArrayList<String>(64)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 500) {
            val (n, depth) = queue.removeFirst()
            visited++
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != n.text?.toString() }?.let(out::add)
            if (depth < 25) for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it to depth + 1) }
        }
        return out
    }
}
