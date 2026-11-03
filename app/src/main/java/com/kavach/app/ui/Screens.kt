package com.kavach.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.audio.SAMPLE_RATE
import com.kavach.app.audio.audioSourceOf
import com.kavach.app.audio.rmsDb
import com.kavach.app.container
import com.kavach.app.engine.*
import com.kavach.app.util.PdfExporter
import com.kavach.app.util.Wav
import com.kavach.core.model.*
import com.kavach.core.report.ReportBuilder
import com.kavach.core.sim.Scenario
import com.kavach.core.sim.ScenarioLoader
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.max

private fun copy(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(label); hint?.let { Text(it, fontSize = 12.sp, color = Color.Gray) } }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ContactEditor(title: String, list: List<ContactDto>, max: Int, onChange: (List<ContactDto>) -> Unit) {
    Section(title) {
        list.forEachIndexed { i, c ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Name", c.name, { n -> onChange(list.toMutableList().also { it[i] = c.copy(name = n) }) }, modifier = Modifier.weight(1f))
                Field("Number", c.number, { n -> onChange(list.toMutableList().also { it[i] = c.copy(number = n) }) }, modifier = Modifier.weight(1.3f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("Relation (e.g. son)", c.relation, { n -> onChange(list.toMutableList().also { it[i] = c.copy(relation = n) }) }, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(list.toMutableList().also { it.removeAt(i) }) }) { Text("Remove") }
            }
        }
        if (list.size < max) OutlinedButton(onClick = { onChange(list + ContactDto()) }) { Text("Add") }
    }
}

@Composable
fun SettingsScreen() {
    val c = LocalContext.current.container()
    val s by c.settings.state.collectAsState()
    fun up(f: (SettingsState) -> SettingsState) = c.settings.update(f)
    Page {
        Section("You") {
            Field("Your name (used in guardian SMS)", s.userName, { n -> up { it.copy(userName = n) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Language:")
                FilterChip(selected = s.language == "EN", onClick = { up { it.copy(language = "EN") } }, label = { Text("English") })
                FilterChip(selected = s.language == "HI", onClick = { up { it.copy(language = "HI") } }, label = { Text("हिन्दी") })
            }
            ToggleRow("Family safe word is set", s.safeWordSet, { v -> up { it.copy(safeWordSet = v) } }, "Only a flag. The word itself is never stored on the phone.")
            ToggleRow("Speak the tier-1 nudge aloud", s.speakNudges, { v -> up { it.copy(speakNudges = v) } }, "Tier 2/3 warnings are always spoken")
        }
        ContactEditor("Guardians (get the SMS alert, can reply HOLD)", s.guardians, 2) { l -> up { it.copy(guardians = l) } }
        ContactEditor("Family to call back (the big button)", s.family, 3) { l -> up { it.copy(family = l) } }

        Section("Language model") {
            Field("Ollama URL (on-device or via adb reverse)", s.ollamaUrl, { n -> up { it.copy(ollamaUrl = n.trim()) } })
            Field("Model", s.ollamaModel, { n -> up { it.copy(ollamaModel = n.trim()) } })
            Field("Local timeout (ms) before falling back", s.llmTimeoutMs.toString(), { n -> n.toLongOrNull()?.let { v -> up { it.copy(llmTimeoutMs = v.coerceIn(1000, 60000)) } } })
            ToggleRow("Allow cloud fallback", s.cloudEnabled, { v -> up { it.copy(cloudEnabled = v) } }, "OFF by default. Sends ONLY the short transcript text, never audio or numbers.")
            Field("Cloud API key (stays on this phone)", s.cloudApiKey, { n -> up { it.copy(cloudApiKey = n.trim()) } }, password = true)
        }

        Section("Audio") {
            Text("Source used during calls (set from the Day-0 test):", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("VOICE_RECOGNITION", "MIC", "CAMCORDER").forEach { src ->
                    FilterChip(selected = s.audioSource == src, onClick = { up { it.copy(audioSource = src) } }, label = { Text(src.replace("VOICE_", "V_"), fontSize = 11.sp) })
                }
            }
            ToggleRow("Companion listener (option C)", s.companionEnabled, { v -> up { it.copy(companionEnabled = v) } }, "Receives transcripts from the laptop. Needs a token.")
            Field("Companion token (min 8 chars)", s.companionToken, { n -> up { it.copy(companionToken = n.trim()) } }, password = true)
        }

        Section("Demo data") {
            Button(onClick = { c.seedDemoBaseline() }) { Text("Seed demo payment history") }
            Text("Usual payees and amounts, so 'new payee' and '8x usual' fire in the right places.", fontSize = 12.sp)
        }
    }
}

// ----------------------------------------------------------------------------------------------- Simulate

object SimPlayer {
    @Volatile private var job: Job? = null
    val status = kotlinx.coroutines.flow.MutableStateFlow("idle")

    fun stop(c: com.kavach.app.Container) {
        job?.cancel(); job = null
        c.sim?.stop(); c.sim = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { c.overlay.dismiss() }
        status.value = "stopped"
    }

    /** Re-times a scenario onto a virtual clock and feeds the REAL orchestrator, overlay, TTS (SMS is a dry run). */
    fun play(c: com.kavach.app.Container, s: Scenario, speed: Double) {
        stop(c)
        val start = ScenarioLoader.startEpochMs(s)
        val simNow = java.util.concurrent.atomic.AtomicLong(start)
        val engine = c.newSimEngine { simNow.get() }
        val events = s.events.sortedBy { it.t }
        val endT = (events.lastOrNull()?.t ?: 0) + 30
        job = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            var t = 0
            var idx = 0
            var nextTick = 5
            while (t <= endT && isActive) {
                val nextEventT = events.getOrNull(idx)?.t ?: Int.MAX_VALUE
                val target = minOf(nextEventT, nextTick, endT + 1)
                if (target > t) delay(((target - t) * 1000 / speed).toLong())
                t = target
                simNow.set(start + t * 1000L)
                if (t == nextTick) { engine.emit(Tick(simNow.get())); nextTick += 5 }
                while (idx < events.size && events[idx].t <= t) { engine.emit(ScenarioLoader.toEvent(s, events[idx])); idx++ }
                status.value = "${s.id}  t=${t}s / ${endT}s"
            }
            status.value = "finished ${s.id}"
        }
    }
}

@Composable
fun SimulateScreen() {
    val c = LocalContext.current.container()
    val scenarios = remember { ScenarioLoader.loadBundled() }
    val status by SimPlayer.status.collectAsState()
    var speed by remember { mutableDoubleStateOf(1.0) }
    val simLog by (c.sim?.log ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    Page {
        Text("Plays a scenario through the real engine, overlay and spoken warnings. Guardian SMS is a dry run. This is your backup demo if the live call fails.", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Speed:")
            listOf(1.0, 2.0, 4.0, 8.0).forEach { v -> FilterChip(selected = speed == v, onClick = { speed = v }, label = { Text("${v.toInt()}x") }) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { SimPlayer.stop(c) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("Stop") }
        }
        Text("Status: $status", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        if (!c.overlay.canDraw()) Text("⚠ Overlay not available: enable the accessibility service or 'Display over other apps' (Setup).", color = Color(0xFFB3261E), fontSize = 13.sp)
        scenarios.sortedWith(compareBy({ it.kind != "scam" }, { it.id })).forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.id, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(s.description, fontSize = 12.sp, color = Color.Gray)
                        Text(if (s.kind == "scam") "SCAM" else "NORMAL", fontSize = 11.sp, color = if (s.kind == "scam") Color(0xFFB3261E) else Color(0xFF1B7F3B), fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { SimPlayer.play(c, s, speed) }) { Text("Play") }
                }
            }
        }
        Section("Sim log") { simLog.takeLast(25).reversed().forEach { Text("${it.kind}: ${it.text}", fontFamily = FontFamily.Monospace, fontSize = 10.sp) } }
    }
}

// ----------------------------------------------------------------------------------------------- Day-0 audio test

private data class AudioResult(val label: String, val source: String, val peak: Int, val db: Float, val file: String?, val error: String?) {
    val verdict get() = when {
        error != null -> "ERROR: $error"
        db < -75f || peak < 40 -> "SILENT: this source is blocked during the call"
        db < -55f -> "VERY QUIET: probably only room noise"
        else -> "HAS SIGNAL"
    }
}

@Composable
fun AudioTestScreen() {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("cellular") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<AudioResult>() }
    val micGranted = ctx.hasPermission(Manifest.permission.RECORD_AUDIO)
    val settings by c.settings.state.collectAsState()
    val level by c.audioLevelDb.collectAsState()

    Page {
        Text("The spec's #1 risk: Android may give the phone call exclusive mic access and hand other apps silence. This 4-minute test decides which audio option works on THIS phone.", fontSize = 13.sp)
        Section("How to run") {
            Text("1. Start a normal call on SPEAKERPHONE (or a WhatsApp call), set the label below.\n2. Tap Start. The caller keeps talking for ~80 s: it records 20 s on each source.\n3. Read the verdict. Use the first source that says HAS SIGNAL.\n4. Repeat with a WhatsApp call (change the label).\nAll SILENT = use option C (laptop companion) or option D.", fontSize = 13.sp)
            Field("Label (cellular / whatsapp)", label, { label = it.filter { ch -> ch.isLetterOrDigit() }.take(16) })
            if (!micGranted) Text("Grant the microphone permission on the Setup screen first.", color = Color(0xFFB3261E))
            Button(enabled = micGranted && !running, onClick = {
                running = true; results.clear()
                scope.launch(Dispatchers.IO) {
                    for (src in listOf("VOICE_RECOGNITION", "MIC", "CAMCORDER", "UNPROCESSED")) {
                        for (n in 3 downTo 1) { progress = "$src starts in $n..."; delay(1000) }
                        progress = "Recording $src for 20 s..."
                        results += record(ctx, src, label)
                    }
                    progress = "Done"; running = false
                }
            }) { Text(if (running) "Recording..." else "Start 20 s per source") }
            Text(progress, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("Live mic level: ${"%.0f".format(level)} dBFS (only moves while Kavach is capturing)", fontSize = 12.sp)
        }
        results.forEach { r ->
            Section("${r.source}  [${r.label}]") {
                Text(r.verdict, fontWeight = FontWeight.Bold, color = if (r.verdict.startsWith("HAS")) Color(0xFF1B7F3B) else Color(0xFFB3261E))
                Text("peak=${r.peak}  rms=${"%.1f".format(r.db)} dBFS", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                r.file?.let { Text(it, fontSize = 10.sp) }
                if (r.verdict.startsWith("HAS")) Button(onClick = { c.settings.update { it.copy(audioSource = r.source) } }) {
                    Text(if (settings.audioSource == r.source) "In use" else "Use this source")
                }
            }
        }
        Text("Pull the WAVs to listen: adb pull /sdcard/Android/data/com.kavach.app/files/day0 (never commit them; *.wav is gitignored).", fontSize = 11.sp)
    }
}

@Suppress("MissingPermission")
private fun record(ctx: Context, source: String, label: String): AudioResult {
    val total = SAMPLE_RATE * 20
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = try { AudioRecord(audioSourceOf(source), SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE * 2)) } catch (e: Exception) {
        return AudioResult(label, source, 0, -90f, null, e.javaClass.simpleName)
    }
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return AudioResult(label, source, 0, -90f, null, "source not available") }
    val pcm = ShortArray(total)
    var n = 0
    try {
        rec.startRecording()
        while (n < total) { val r = rec.read(pcm, n, minOf(4096, total - n)); if (r <= 0) break; n += r }
    } finally { runCatching { rec.stop() }; rec.release() }
    var peak = 0
    for (i in 0 until n) peak = max(peak, kotlin.math.abs(pcm[i].toInt()))
    val file = File(ctx.getExternalFilesDir("day0"), "$label-$source.wav")
    Wav.write(file, pcm.copyOf(n), SAMPLE_RATE)
    return AudioResult(label, source, peak, rmsDb(pcm, n), file.absolutePath, null)
}

// ----------------------------------------------------------------------------------------------- Debug

@Composable
fun DebugScreen() {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()
    val log by c.live.log.collectAsState()
    val dump by c.screenDump.collectAsState()
    val settings by c.settings.state.collectAsState()
    var line by remember { mutableStateOf("I am Inspector Sharma from Cyber Crime. You will be arrested. Do not tell anyone. Transfer money now.") }
    var ping by remember { mutableStateOf("") }
    val live = c.live
    fun now() = live.now()
    val scammer = "+91 99887 76655"

    Page {
        Text("⚠ Buttons here inject events into the LIVE engine (overlay, SMS and speech are real, SMS goes to your guardians).", fontSize = 12.sp, color = Color(0xFFB45F06))
        Section("Inject events") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { c.onCallEvents(listOf(CallStarted(now(), scammer, isInContacts = false))) }) { Text("Unknown call") }
                OutlinedButton(onClick = { c.onCallEvents(listOf(CallEnded(now(), 60))) }) { Text("Call ends") }
            }
            Field("Transcript line (as if heard on the call)", line, { line = it })
            Button(onClick = { live.emit(TranscriptChunk(now(), line, "auto", com.kavach.core.model.Speaker.CALLER)) }) { Text("Send transcript") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { live.emit(PaymentIntent(now(), "PhonePe", PayStage.OPENED)) }) { Text("Pay app") }
                OutlinedButton(onClick = { live.emit(PaymentIntent(now(), "PhonePe", PayStage.AMOUNT_ENTERED, 50000.0, "verify.pay@oksbi")) }) { Text("₹50k") }
                OutlinedButton(onClick = { live.emit(PaymentIntent(now(), "PhonePe", PayStage.PIN_SCREEN, 50000.0, "verify.pay@oksbi")) }) { Text("PIN") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { live.emit(PaymentDismissed(now())) }) { Text("Pay closed") }
                OutlinedButton(onClick = { live.emit(RemoteAccessDetected(now(), "AnyDesk")) }) { Text("AnyDesk") }
                OutlinedButton(onClick = { live.emit(MessageReceived(now(), "+91 98888 77777", "Your KYC will expire today, account blocked. https://sbi-kyc.xyz/u")) }) { Text("Scam SMS") }
            }
            settings.guardians.firstOrNull { it.number.isNotBlank() }?.let { g ->
                OutlinedButton(onClick = { live.emit(GuardianHold(now(), g.number)) }) { Text("Guardian replies HOLD (${g.name})") }
            }
        }
        Section("Language model") {
            Button(onClick = {
                ping = "pinging..."
                scope.launch(Dispatchers.IO) {
                    ping = try {
                        val r = DynamicLlmClient(c.settings, live.llmStatus).analyze(com.kavach.core.llm.LlmRequest(com.kavach.core.llm.Prompts.TRANSCRIPT_SYSTEM, com.kavach.core.llm.Prompts.transcriptUser(line, 3, false, false)))
                        "${r.provider} ${r.latencyMs} ms\n${r.text.take(400)}"
                    } catch (e: Exception) { "FAILED: ${e.message}" }
                }
            }) { Text("Ping model with the transcript line") }
            Text(ping, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Section("Last accessibility screen (for tuning a UPI app)") {
            val d = dump
            if (d == null) Text("Nothing captured yet. Open a payment app with the accessibility service on.", fontSize = 12.sp) else {
                Text("${d.pkg}\n${d.summary}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Button(onClick = { copy(ctx, "screen dump", "pkg=${d.pkg}\n${d.summary}\n---\n" + d.texts.joinToString("\n")) }) { Text("Copy dump (paste into ScreenParserTest)") }
                Text(d.texts.take(40).joinToString(" | "), fontSize = 10.sp)
            }
        }
        Section("Log (newest first)") {
            Button(onClick = { copy(ctx, "kavach log", log.joinToString("\n") { "${it.kind}: ${it.text}" }) }) { Text("Copy log") }
            log.takeLast(80).reversed().forEach { Text("${it.kind}: ${it.text}", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
    }
}

// ----------------------------------------------------------------------------------------------- Report

@Composable
fun ReportScreen(moneyHint: Boolean) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val s by c.settings.state.collectAsState()
    var moneySent by remember(moneyHint) { mutableStateOf(moneyHint) }
    val engine = remember { c.reportEngine() }
    val session = remember { engine.orchestrator.reportableSession() }
    if (session == null) {
        Page {
            Text("No suspicious session yet. Run a scam from Simulate, then come back.", fontSize = 15.sp)
        }
        return
    }
    val report = remember(moneySent, session) { ReportBuilder.build(session, moneySent, userName = "I") }
    var narrative by remember(report) { mutableStateOf(report.narrative) }
    val fields = report.fields.map { if (it.label == "Brief description") it.copy(value = narrative) else it }
    val text = remember(narrative, report) { ReportBuilder.renderText(report).replace(report.narrative, narrative) }

    Page {
        if (moneySent) {
            Button(onClick = { Actions.call(ctx, report.actions.helpline) }, modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) {
                Text("CALL 1930 NOW", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("Money was sent. Calling within the first hour gives the best chance of the bank freezing the funds. Then file below.", fontSize = 14.sp)
        }
        ToggleRow("Money was sent", moneySent, { moneySent = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!moneySent) OutlinedButton(onClick = { Actions.call(ctx, report.actions.helpline) }) { Text("Call 1930") }
            Button(onClick = { Actions.openUrl(ctx, report.actions.portalUrl) }) { Text("cybercrime.gov.in") }
            if (!moneySent) OutlinedButton(onClick = { Actions.openUrl(ctx, report.actions.chakshuUrl) }) { Text("Chakshu") }
        }
        Section("Review before sharing (it goes to police in ${s.userName.ifBlank { "your" }} name)") {
            OutlinedTextField(value = narrative, onValueChange = { narrative = it }, label = { Text("Description (editable)") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
        }
        fields.forEach { f ->
            Section(f.label) {
                Text(f.value, fontSize = 14.sp)
                TextButton(onClick = { copy(ctx, f.label, f.value) }) { Text("Copy") }
            }
        }
        Button(onClick = { ctx.startActivity(android.content.Intent.createChooser(PdfExporter.share(ctx, report, text), "Share complaint PDF").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Share as PDF")
        }
        if (report.evidenceQuotes.isNotEmpty()) Section("Evidence quotes") { report.evidenceQuotes.forEach { Text("• \"$it\"", fontSize = 13.sp) } }
        Section("Timeline") { report.timelineText.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) } }
    }
}
