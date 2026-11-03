package com.kavach.app.engine

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.net.toUri
import kotlin.concurrent.thread

/**
 * Asks the Termux app on this phone to start Ollama. The command is a constant ([OLLAMA_SERVE_SHELL]):
 * loopback only, and it does nothing if `ollama` is already running. This never waits for the server.
 */
object TermuxOllama {
    const val PACKAGE = "com.termux"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val READINESS_LABEL = "Local AI auto-start (Termux)"
    const val INSTALL_URL = "https://github.com/termux/termux-app/releases"

    // Termux's own prefix, not Kavach storage. getFilesDir() would be the wrong directory.
    @SuppressLint("SdCardPath")
    private const val PATH = "/data/data/com.termux/files/usr/bin/bash"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    fun isInstalled(ctx: Context): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun isReady(ctx: Context): Boolean = isInstalled(ctx) && ctx.hasPermission(RUN_COMMAND_PERMISSION)

    /** State sentence for the Setup row. Kavach cannot see `~/.termux/termux.properties`, so that step stays in the text. */
    fun readinessDetail(ctx: Context): String {
        val installed = isInstalled(ctx)
        val granted = ctx.hasPermission(RUN_COMMAND_PERMISSION)
        return when {
            !installed -> "Not installed. Use F-Droid or GitHub, not the Play Store. Then set allow-external-apps=true once."
            !granted -> "Permission not granted. Allow Kavach to run commands in Termux, and set allow-external-apps=true once."
            else -> "Permission granted. One-time: allow-external-apps=true in ~/.termux/termux.properties, then force-stop Termux."
        }
    }

    fun runCommandIntent(): Intent {
        // Bundle.putStringArray, not Intent.putExtra(Array): Kotlin can pick the Serializable overload,
        // and Termux reads the arguments with getStringArrayExtra.
        val extras = Bundle()
        extras.putString(EXTRA_PATH, PATH)
        extras.putStringArray(EXTRA_ARGS, arrayOf("-c", OLLAMA_SERVE_SHELL))
        extras.putBoolean(EXTRA_BACKGROUND, true)
        return Intent().apply {
            component = ComponentName(PACKAGE, SERVICE)
            setPackage(PACKAGE)
            action = ACTION
            putExtras(extras)
        }
    }

    /** Opens Termux so the user can do the one-time properties edit. Returns false if it cannot be opened. */
    fun openApp(ctx: Context): Boolean {
        val launch = try {
            ctx.packageManager.getLaunchIntentForPackage(PACKAGE)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        } ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(launch)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** GitHub release page (arm64 APK). The Setup row also names F-Droid. Play Store Termux cannot run this intent. */
    fun openInstallSteps(ctx: Context): Boolean {
        val view = Intent(Intent.ACTION_VIEW, INSTALL_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(view)
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Fires [TermuxOllama.runCommandIntent] on a daemon thread. The caller (the engine thread, or the service) is not blocked.
 * Failures are one Debug line via [attachLog].
 */
class TermuxOllamaAutostart(context: Context) {
    private val app = context.applicationContext
    @Volatile private var note: (String) -> Unit = {}

    val gate = OllamaAutostartGate(launch = { requestStart() })

    fun attachLog(log: (String) -> Unit) {
        note = log
    }

    fun onProtectionOn(ollamaUrl: String) {
        try {
            gate.onProtectionEnabled(ollamaUrl)
        } catch (e: Exception) {
            log("Termux auto-start failed: ${e.javaClass.simpleName}")
        }
    }

    private fun requestStart() {
        try {
            thread(name = "kavach-termux-ollama", isDaemon = true) {
                val line = try {
                    val started = app.startService(TermuxOllama.runCommandIntent())
                    if (started == null) "Termux auto-start failed: service missing" else "Asked Termux to start Ollama"
                } catch (e: Exception) {
                    "Termux auto-start failed: ${e.javaClass.simpleName}"
                }
                log(line)
            }
        } catch (e: Exception) {
            log("Termux auto-start failed: ${e.javaClass.simpleName}")
        }
    }

    private fun log(line: String) {
        try {
            note(line)
        } catch (_: Exception) {
            // The Debug log is best-effort. Never fail the start attempt because the log sink threw.
        }
    }
}
