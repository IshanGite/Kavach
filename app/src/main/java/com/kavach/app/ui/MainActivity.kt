package com.kavach.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kavach.app.KavachService
import com.kavach.app.container
import com.kavach.app.sense.CallScreeningRole

class MainActivity : ComponentActivity() {
    private val route = mutableStateOf("home")
    private val money = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consume(intent)
        setContent { KavachTheme { AppRoot(route, money) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consume(intent)
    }

    private fun consume(i: Intent?) {
        i?.getStringExtra(EXTRA_ROUTE)?.let { route.value = it }
        money.value = i?.getBooleanExtra(EXTRA_MONEY, false) ?: false
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_MONEY = "money"
    }
}

/** Re-evaluates something every time the app returns to the foreground (after the user visits a system settings page). */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(route: MutableState<String>, money: MutableState<Boolean>) {
    BackHandler(enabled = route.value != "home") { route.value = "home" }
    val title = mapOf(
        "home" to "Kavach", "setup" to "Setup", "settings" to "Settings", "simulate" to "Simulate (backup demo)",
        "audio" to "Day-0 audio test", "debug" to "Debug", "report" to "Complaint report", "live" to "Live view", "preflight" to "Pre-flight check",
    )[route.value] ?: "Kavach"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (route.value != "home") TextButton(onClick = { route.value = "home" }) { Text("Back") } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (route.value) {
                "setup" -> SetupScreen()
                "settings" -> SettingsScreen()
                "simulate" -> SimulateScreen()
                "audio" -> AudioTestScreen()
                "debug" -> DebugScreen()
                "report" -> ReportScreen(money.value)
                "live" -> LiveScreen()
                "preflight" -> PreflightScreen()
                else -> HomeScreen(go = { route.value = it })
            }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
fun HomeScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val settings by c.settings.state.collectAsState()
    val asr by c.asrState.collectAsState()
    val d by c.live.latest.collectAsState()
    val llm by c.live.llmStatus.collectAsState()
    val level by c.audioLevelDb.collectAsState()
    val tick = rememberResumeTick()
    val checks = remember(tick, settings) { Health.checks(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Page {
        Section("Protection") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(if (settings.protectionEnabled) "ON" else "OFF", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (settings.protectionEnabled) Color(0xFF1B7F3B) else Color.Gray)
                    Text("One switch pauses everything.", fontSize = 13.sp)
                }
                Switch(checked = settings.protectionEnabled, onCheckedChange = { on ->
                    c.settings.update { it.copy(protectionEnabled = on) }
                    if (on) {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        KavachService.start(ctx)
                    } else KavachService.stop(ctx)
                })
            }
        }

        Section("Live status") {
            val r = d?.result
            Text("State: ${d?.state ?: "IDLE"}    Tier: ${d?.tier?.level ?: 0}", fontFamily = FontFamily.Monospace)
            Text("Context C=${r?.contextScore ?: 0}   Language L=${r?.languageScore ?: "-"}   Risk R=${r?.risk ?: 0}", fontFamily = FontFamily.Monospace)
            LinearProgressIndicator(progress = { (r?.risk ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().height(10.dp),
                color = when ((d?.tier?.level ?: 0)) { 0 -> Color(0xFF1B7F3B); 1 -> Color(0xFFB45F06); else -> Color(0xFFB3261E) })
            Text("Language model: $llm", fontSize = 13.sp)
            Text("ASR: ${asr.name}", fontSize = 13.sp)
            Text("Mic level: ${"%.0f".format(level)} dBFS", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }

        val bad = checks.count { !it.ok }
        Section(if (bad == 0) "Readiness: all set" else "Readiness: $bad item(s) need attention") {
            checks.forEach { Text((if (it.ok) "✅ " else "❌ ") + it.label, fontSize = 14.sp) }
            Button(onClick = { go("setup") }) { Text(if (bad == 0) "Review setup" else "Fix now") }
        }

        Section("Tools") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { go("simulate") }, modifier = Modifier.weight(1f)) { Text("Simulate") }
                Button(onClick = { go("audio") }, modifier = Modifier.weight(1f)) { Text("Audio test") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { go("debug") }, modifier = Modifier.weight(1f)) { Text("Debug") }
                OutlinedButton(onClick = { go("settings") }, modifier = Modifier.weight(1f)) { Text("Settings") }
            }
            Button(onClick = { go("preflight") }, modifier = Modifier.fillMaxWidth()) { Text("Pre-flight check (before the demo)") }
            Button(onClick = { go("live") }, modifier = Modifier.fillMaxWidth()) { Text("Live view (for the judges' screen)") }
            OutlinedButton(onClick = { go("report") }, modifier = Modifier.fillMaxWidth()) { Text("Complaint report") }
        }
    }
}

@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    var roleTick by remember { mutableIntStateOf(0) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { roleTick++ }
    val checks = remember(tick, roleTick) { Health.checks(ctx) }
    val callerIdAvailable = remember(tick, roleTick) { CallScreeningRole.isAvailable(ctx) }
    val callerIdHeld = remember(tick, roleTick) { CallScreeningRole.isHeld(ctx) }
    fun open(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val pkg = Uri.parse("package:${ctx.packageName}")

    Page {
        Text("Grant each item in order. Every one is explained, and nothing leaves the phone by default.", fontSize = 14.sp)
        Section("Runtime permissions") {
            Text("Phone, call log, contacts, microphone, SMS, notifications, calling.", fontSize = 13.sp)
            Button(onClick = {
                val perms = mutableListOf(
                    Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.CALL_PHONE,
                )
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(perms.toTypedArray())
            }) { Text("Grant runtime permissions") }
        }
        Section("Caller ID (call screening role)") {
            Text("Lets Kavach read the incoming phone number and whether it is a saved contact. Kavach always allows the call. Until this is granted, call detection stays on the phone-state broadcast alone.", fontSize = 13.sp)
            if (callerIdAvailable) {
                Text(if (callerIdHeld) "Granted." else "Tap once. Android confirms the role, including when another caller-ID app has it.", fontSize = 13.sp)
                Button(
                    onClick = { CallScreeningRole.requestIntent(ctx)?.let { roleLauncher.launch(it) } },
                    enabled = !callerIdHeld,
                ) { Text(if (callerIdHeld) "Caller ID role granted" else "Request caller ID role") }
            } else {
                Text("This phone has no call screening role. Call detection stays on the phone-state broadcast.", fontSize = 13.sp)
            }
        }
        Section("Accessibility service (most important)") {
            Text("Lets Kavach see that a payment app is open, the amount and payee, and the UPI PIN screen, so it can pause BEFORE the PIN. It also draws the pause screen.", fontSize = 13.sp)
            Text("Sideloaded APK on Android 13+: first open App info > ⋮ > 'Allow restricted settings', otherwise the toggle is greyed out.", fontSize = 13.sp, color = Color(0xFFB45F06))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }) { Text("App info") }
                Button(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
            }
        }
        Section("Notification access") {
            Text("Reads WhatsApp / Telegram / UPI notifications for scam messages and collect requests, and detects AnyDesk-style apps.", fontSize = 13.sp)
            Button(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access") }
        }
        Section("Display over other apps") {
            Text("Fallback for the pause screen if the accessibility overlay is unavailable.", fontSize = 13.sp)
            Button(onClick = { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) }) { Text("Allow overlay") }
        }
        Section("Battery") {
            Text("Exempt Kavach (and Termux, if it runs Ollama) so Android does not kill them mid-call.", fontSize = 13.sp)
            Button(onClick = { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) }) { Text("Ignore battery optimisation") }
        }
        Section("Status") { checks.forEach { Text((if (it.ok) "✅ " else "❌ ") + it.label + "  -  " + it.why, fontSize = 13.sp) } }
    }
}
