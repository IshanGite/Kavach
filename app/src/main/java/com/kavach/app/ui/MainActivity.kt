package com.kavach.app.ui

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kavach.app.KavachService
import com.kavach.app.audio.SherpaWhisperAsr
import com.kavach.app.container
import com.kavach.app.engine.TermuxOllama
import com.kavach.app.engine.hasPermission
import com.kavach.app.sense.CallScreeningRole

class MainActivity : ComponentActivity() {
    private val route = mutableStateOf("home")
    private val money = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        KavachPay.ROUTE to KavachPay.TITLE,
    )[route.value] ?: "Kavach"
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    scrolledContainerColor = scheme.background,
                    titleContentColor = scheme.primary,
                    navigationIconContentColor = scheme.onSurface,
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(title, fontWeight = FontWeight.ExtraBold)
                    }
                },
                navigationIcon = {
                    if (route.value != "home") {
                        TextButton(
                            onClick = { route.value = "home" },
                            colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Back", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
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
                KavachPay.ROUTE -> KavachPayScreen()
                else -> HomeScreen(go = { route.value = it })
            }
        }
    }
}

@Composable
fun Section(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

private data class HomeModel(
    val protectionOn: Boolean,
    val stateLabel: String,
    val tierLevel: Int,
    val contextScore: Int,
    val languageScore: Int?,
    val risk: Int,
    val ruleCodes: List<com.kavach.core.risk.RuleCode> = emptyList(),
    val lang: com.kavach.core.intervention.Lang = com.kavach.core.intervention.Lang.EN,
    val asrLine: String,
    val llmLine: String,
    val micDb: Float,
    val callerIdOn: Boolean,
    val cloudFallback: Boolean,
    val checks: List<Check>,
)

@Composable
fun HomeScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val settings by c.settings.state.collectAsState()
    val asr by c.asrState.collectAsState()
    val whisperStatus by c.whisperStatus.collectAsState()
    val d by c.live.latest.collectAsState()
    val llm by c.live.llmStatus.collectAsState()
    val level by c.audioLevelDb.collectAsState()
    val tick = rememberResumeTick()
    val checks = remember(tick, settings) { Health.checks(ctx) }
    val callerIdOn = remember(tick) { CallScreeningRole.isHeld(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val asrLine = if (asr is SherpaWhisperAsr) whisperStatus.ifBlank { asr.name } else asr.name

    HomeContent(
        model = HomeModel(
            protectionOn = settings.protectionEnabled,
            stateLabel = d?.state?.name ?: "IDLE",
            tierLevel = d?.tier?.level ?: 0,
            contextScore = d?.result?.contextScore ?: 0,
            languageScore = d?.result?.languageScore,
            risk = d?.result?.risk ?: 0,
            ruleCodes = d?.result?.reasonCodes().orEmpty(),
            lang = settings.lang(),
            asrLine = asrLine,
            llmLine = llm,
            micDb = level,
            callerIdOn = callerIdOn,
            cloudFallback = settings.cloudEnabled,
            checks = checks,
        ),
        onProtectionChange = { on ->
            c.settings.update { it.copy(protectionEnabled = on) }
            if (on) {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                KavachService.start(ctx)
            } else KavachService.stop(ctx)
        },
        go = go,
    )
}

@Composable
private fun HomeContent(model: HomeModel, onProtectionChange: (Boolean) -> Unit, go: (String) -> Unit) {
    val bad = model.checks.count { !it.ok }
    val accent = tierAccent(model.tierLevel)
    val riskFraction by animateFloatAsState((model.risk / 100f).coerceIn(0f, 1f), tween(600), label = "risk")
    val speechOn = !model.asrLine.startsWith("none") && !model.asrLine.contains("loading")
    val modelOn = model.llmLine.isNotBlank() && model.llmLine != "not used yet" && !model.llmLine.startsWith("no model")
    val llmDetail = if (model.cloudFallback) "${model.llmLine} · cloud fallback on" else model.llmLine
    val dark = isSystemInDarkTheme()
    val heroContainer = if (model.protectionOn) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val heroTitle = if (model.protectionOn) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val heroSub = if (model.protectionOn) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val circle = when {
        !model.protectionOn -> MaterialTheme.colorScheme.surfaceVariant
        dark -> Color(0xFF7DDBC4)
        else -> Safe
    }
    val circleIcon = when {
        !model.protectionOn -> MaterialTheme.colorScheme.onSurfaceVariant
        dark -> Color(0xFF05382C)
        else -> Color.White
    }

    Page {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = heroContainer),
            border = BorderStroke(1.dp, if (model.protectionOn) Safe else MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(16.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(64.dp).background(circle, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = circleIcon, modifier = Modifier.size(36.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (model.protectionOn) "Protected" else "Off",
                        color = heroTitle,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp,
                        lineHeight = 34.sp,
                    )
                    Text("One switch pauses everything.", color = heroSub, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = model.protectionOn,
                    onCheckedChange = onProtectionChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Safe,
                        checkedBorderColor = Safe,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
        }

        Section("Risk") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("${riskFraction.times(100).toInt()}", color = accent, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 60.sp)
                    Text(
                        "State: ${model.stateLabel}    Tier: ${model.tierLevel}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TierChip(model.tierLevel)
            }
            LinearProgressIndicator(
                progress = { riskFraction },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScoreMeter(RiskLabels.name(RiskLabels.Bar.SITUATION, model.lang), RiskLabels.helper(RiskLabels.Bar.SITUATION, model.lang, model.ruleCodes), model.contextScore.toString(), model.contextScore / 100f, MaterialTheme.colorScheme.primary)
                ScoreMeter(RiskLabels.name(RiskLabels.Bar.SAYING, model.lang), RiskLabels.helper(RiskLabels.Bar.SAYING, model.lang, languageScore = model.languageScore), model.languageScore?.toString() ?: "-", (model.languageScore ?: 0) / 100f, if (model.languageScore == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.secondary)
                ScoreMeter(RiskLabels.name(RiskLabels.Bar.RISK, model.lang), RiskLabels.helper(RiskLabels.Bar.RISK, model.lang, tierLevel = model.tierLevel), model.risk.toString(), model.risk / 100f, accent)
            }
            SignalChip(Icons.Filled.Mic, "On-device speech", model.asrLine, speechOn)
            SignalChip(Icons.Filled.Psychology, "Local AI", llmDetail, modelOn)
            SignalChip(Icons.Filled.ContactPhone, "Caller ID", if (model.callerIdOn) "Granted" else "Not granted", model.callerIdOn)
            Text(
                "Mic level: ${"%.0f".format(model.micDb)} dBFS",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(if (bad == 0) "Readiness: all set" else "Readiness: $bad item(s) need attention") {
            model.checks.forEach { StatusLine(it.ok, it.label) }
            WideButton(if (bad == 0) "Review setup" else "Fix now", onClick = { go("setup") })
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WideButton(KavachPay.NAV_LABEL, onClick = { go(KavachPay.ROUTE) }, icon = Icons.Filled.Payments)
            WideButton("Simulate", onClick = { go("simulate") }, icon = Icons.Filled.PlayArrow)
            WideButton("Live view (for the judges' screen)", onClick = { go("live") }, icon = Icons.Filled.Visibility)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Debug", Icons.Filled.BugReport) { go("debug") }
                SmallButton("Settings", Icons.Filled.Settings) { go("settings") }
                SmallButton("Audio test", Icons.Filled.GraphicEq) { go("audio") }
            }
            WideButton("Pre-flight check (before the demo)", onClick = { go("preflight") }, icon = Icons.Filled.HealthAndSafety, outlined = true)
            WideButton("Complaint report", onClick = { go("report") }, icon = Icons.Filled.Description, outlined = true)
        }
    }
}

@Composable
private fun RowScope.ScoreMeter(letter: String, caption: String, value: String, fraction: Float, color: Color) {
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = letter)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(letter, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = color, modifier = Modifier.weight(1f))
            Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        LinearProgressIndicator(
            progress = { anim },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SignalChip(icon: ImageVector, title: String, detail: String, active: Boolean) {
    val bg = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(detail, color = fg, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun WideButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, outlined: Boolean = false, enabled: Boolean = true) {
    val mod = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    val shape = RoundedCornerShape(16.dp)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = mod, enabled = enabled, shape = shape, contentPadding = padding) {
            WideLabel(icon, text)
        }
    } else {
        Button(onClick = onClick, modifier = mod, enabled = enabled, shape = shape, contentPadding = padding) {
            WideLabel(icon, text)
        }
    }
}

@Composable
private fun RowScope.WideLabel(icon: ImageVector?, text: String) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    if (icon != null) Spacer(Modifier.width(30.dp))
}

@Composable
private fun RowScope.SmallButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(8.dp))
            Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
    var termuxTick by remember { mutableIntStateOf(0) }
    val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        termuxTick++
        if (granted) TermuxOllama.openApp(ctx)
    }
    val checks = remember(tick, roleTick, termuxTick) { Health.checks(ctx) }
    val callerIdAvailable = remember(tick, roleTick) { CallScreeningRole.isAvailable(ctx) }
    val callerIdHeld = remember(tick, roleTick) { CallScreeningRole.isHeld(ctx) }
    fun open(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val pkg = Uri.parse("package:${ctx.packageName}")

    Page {
        Text("Grant each item in order. Every one is explained, and nothing leaves the phone by default.", style = MaterialTheme.typography.bodyLarge)
        Section("Runtime permissions", Icons.Filled.Lock) {
            Text("Phone, call log, contacts, microphone, SMS, notifications, calling.", style = MaterialTheme.typography.bodyMedium)
            WideButton("Grant runtime permissions", icon = Icons.Filled.Lock, onClick = {
                val perms = mutableListOf(
                    Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.CALL_PHONE,
                )
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(perms.toTypedArray())
            })
        }
        Section("Caller ID (call screening role)", Icons.Filled.ContactPhone) {
            Text("Lets Kavach read the incoming phone number and whether it is a saved contact. Kavach always allows the call. Until this is granted, call detection stays on the phone-state broadcast alone.", style = MaterialTheme.typography.bodyMedium)
            if (callerIdAvailable) {
                Text(if (callerIdHeld) "Granted." else "Tap once. Android confirms the role, including when another caller-ID app has it.", style = MaterialTheme.typography.bodyMedium)
                WideButton(
                    text = if (callerIdHeld) "Caller ID role granted" else "Request caller ID role",
                    icon = Icons.Filled.Phone,
                    enabled = !callerIdHeld,
                    onClick = { CallScreeningRole.requestIntent(ctx)?.let { roleLauncher.launch(it) } },
                )
            } else {
                Text("This phone has no call screening role. Call detection stays on the phone-state broadcast.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Section("Accessibility service (most important)", Icons.Filled.Settings) {
            Text("Lets Kavach see that a payment app is open, the amount and payee, and the UPI PIN screen, so it can pause BEFORE the PIN. It also draws the pause screen.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Sideloaded APK on Android 13+: first open App info > ⋮ > 'Allow restricted settings', otherwise the toggle is greyed out.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = cautionOnSurface(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("App info", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                Button(
                    onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Accessibility", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            }
        }
        Section("Notification access", Icons.Filled.Notifications) {
            Text("Reads WhatsApp / Telegram / UPI notifications for scam messages and collect requests, and detects AnyDesk-style apps.", style = MaterialTheme.typography.bodyMedium)
            WideButton("Notification access", icon = Icons.Filled.Notifications, onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        }
        Section("Display over other apps", Icons.Filled.Info) {
            Text("Fallback for the pause screen if the accessibility overlay is unavailable.", style = MaterialTheme.typography.bodyMedium)
            WideButton("Allow overlay", icon = Icons.Filled.Info, onClick = { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) })
        }
        Section("Battery", Icons.Filled.Shield) {
            Text("Exempt Kavach (and Termux, if it runs Ollama) so Android does not kill them mid-call.", style = MaterialTheme.typography.bodyMedium)
            WideButton("Ignore battery optimisation", icon = Icons.Filled.Shield, onClick = { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) })
        }
        Section("Status", Icons.Filled.HealthAndSafety) {
            checks.forEach { ch ->
                StatusLine(ch.ok, ch.label, ch.why)
                if (ch.label == TermuxOllama.READINESS_LABEL) {
                    Text(
                        "One-time, inside Termux, then force-stop Termux: mkdir -p ~/.termux and put allow-external-apps=true in ~/.termux/termux.properties. Kavach cannot edit that file. Protection ON then starts ollama serve on 127.0.0.1:11434. If that fails, the keyword analyst still scores the call.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val termuxInstalled = TermuxOllama.isInstalled(ctx)
                    val termuxAllowed = ctx.hasPermission(TermuxOllama.RUN_COMMAND_PERMISSION)
                    WideButton(
                        text = when {
                            !termuxInstalled -> "Termux install steps"
                            !termuxAllowed -> "Allow Termux commands"
                            else -> "Open Termux"
                        },
                        icon = Icons.Filled.Psychology,
                        onClick = {
                            try {
                                when {
                                    !termuxInstalled -> TermuxOllama.openInstallSteps(ctx)
                                    !termuxAllowed -> termuxPermission.launch(TermuxOllama.RUN_COMMAND_PERMISSION)
                                    else -> TermuxOllama.openApp(ctx)
                                }
                            } catch (_: Exception) {
                                // Stay on Setup if Termux is missing or the permission dialog cannot be shown.
                            }
                        },
                    )
                }
            }
        }
    }
}

@Preview(name = "Home", showBackground = true, widthDp = 393, heightDp = 900)
@Composable
private fun HomePreview() {
    KavachTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeContent(previewHome(), onProtectionChange = {}, go = {})
        }
    }
}

@Preview(name = "Home dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B1220, widthDp = 393, heightDp = 900)
@Composable
private fun HomePreviewDark() {
    KavachTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeContent(previewHome(), onProtectionChange = {}, go = {})
        }
    }
}

private fun previewHome() = HomeModel(
    protectionOn = true,
    stateLabel = "MONITORING",
    tierLevel = 2,
    contextScore = 70,
    languageScore = 64,
    risk = 72,
    asrLine = "whisper (detected: hi)",
    llmLine = "ollama in 5400 ms",
    micDb = -32f,
    callerIdOn = true,
    cloudFallback = false,
    checks = listOf(
        Check("Phone state + call log", true, ""),
        Check("Caller ID (call screening role)", true, ""),
        Check("Microphone", true, ""),
        Check("Accessibility service", false, "See which payment app is open"),
    ),
)
