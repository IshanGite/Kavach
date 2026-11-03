package com.kavach.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.container
import com.kavach.app.engine.DynamicLlmClient
import com.kavach.core.intervention.InterventionPlan
import com.kavach.core.intervention.Lang
import com.kavach.core.intervention.TrustedContact
import com.kavach.core.llm.LlmRequest
import com.kavach.core.llm.Prompts
import com.kavach.core.risk.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class Line(val ok: Boolean?, val label: String, val detail: String)

/** One tap before going on stage: is everything actually ready? Tests the overlay, the speech and the model for real, not just permissions. */
@Composable
fun PreflightScreen() {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsState()
    val asr by c.asrState.collectAsState()
    val tick = rememberResumeTick()
    var llm by remember { mutableStateOf<Line?>(null) }

    val hasGuardian = s.guardians.any { it.number.filter(Char::isDigit).length >= 10 }
    val hasFamily = s.family.any { it.number.filter(Char::isDigit).length >= 10 }
    val lines = remember(tick, s, asr.name) {
        buildList {
            Health.checks(ctx).forEach { add(Line(it.ok, it.label, it.why)) }
            add(Line(s.protectionEnabled, "Protection is ON", "Home > switch"))
            add(Line(hasGuardian, "A guardian number is set", "Settings > Guardians, a real phone you can hold up"))
            add(Line(hasFamily, "A family contact is set for the big call button", "Settings > Family"))
            add(Line(c.baseline.historySize() >= 5, "Payment history is seeded (${c.baseline.historySize()} payments)", "Settings > Seed demo payment history"))
            add(Line(true.takeIf { s.audioSource.isNotBlank() }, "Audio source: ${s.audioSource}, ASR: ${asr.name}", "Day-0 test decides this"))
            add(Line(if (s.companionEnabled) s.companionToken.length >= 8 else null, "Companion listener ${if (s.companionEnabled) "enabled" else "off"}", "Needs a token of 8+ characters"))
            add(Line(!s.cloudEnabled, "Cloud fallback is ${if (s.cloudEnabled) "ON (text only)" else "OFF (fully on-device)"}", "Settings > Language model"))
        }
    }
    val sample = InterventionPlan(
        tier = Tier.PAUSE, lang = if (s.language == "HI") Lang.HI else Lang.EN,
        headline = "Pre-flight test: this is what a pause looks like",
        reasons = listOf("The caller claimed to be a CBI officer and told you not to tell anyone.", "You opened PhonePe while this call is still live."),
        mythBuster = "CBI and police never arrest anyone over a video call and never ask for money.",
        callTrusted = TrustedContact(s.family.firstOrNull()?.name ?: "Rahul", s.family.firstOrNull()?.number ?: "100"), callTrustedLabel = "Call ${s.family.firstOrNull()?.name ?: "Rahul"} now",
        askSafeWord = false, callbackVerification = null, cooldownSeconds = 6,
        questions = listOf("Is the caller telling you to keep this secret?"), ifAnyYes = "This is exactly how scammers work.", spokenWarning = "Pre-flight test. Please stop. This looks like a scam.",
    )

    Page {
        Text("Run this at T-15 minutes. Green everywhere, and you have heard and seen the three tests below.", fontSize = 13.sp)
        lines.forEach { l ->
            Text((when (l.ok) { true -> "✅ "; false -> "❌ "; null -> "➖ " }) + l.label, fontSize = 14.sp, fontWeight = if (l.ok == false) FontWeight.Bold else FontWeight.Normal,
                color = if (l.ok == false) Color(0xFFB3261E) else Color.Unspecified)
            if (l.ok == false) Text("   ${l.detail}", fontSize = 12.sp, color = Color.Gray)
        }
        Section("Live tests") {
            Button(onClick = { scope.launch(Dispatchers.Main) {
                val e = c.live; e.pauseUntilMs.value = e.now() + 6_000
                c.overlay.showPause(e, sample); c.speaker.speak(sample.spokenWarning.orEmpty(), sample.lang.tag)
            } }, modifier = Modifier.fillMaxWidth()) { Text("Show a test pause screen + speak it") }
            Text("You should see the full-screen pause, hear the warning, and be able to continue after 6 s.", fontSize = 12.sp)
            Button(onClick = {
                llm = Line(null, "Pinging model...", "")
                scope.launch(Dispatchers.IO) {
                    val t0 = System.currentTimeMillis()
                    llm = try {
                        val r = DynamicLlmClient(c.settings, c.live.llmStatus).analyze(LlmRequest(
                            if (s.compactPrompt) Prompts.TRANSCRIPT_SYSTEM_COMPACT else Prompts.TRANSCRIPT_SYSTEM,
                            Prompts.transcriptUser("I am Inspector Sharma from Cyber Crime. You will be arrested. Do not tell anyone. Transfer money now.", 3, false, false), 300))
                        val ok = r.latencyMs <= s.llmTimeoutMs
                        Line(ok, "Model ${r.provider}: ${r.latencyMs} ms (budget ${s.llmTimeoutMs} ms)", if (ok) "" else "Slower than the timeout: the keyword analyst will cover. Try a smaller model.")
                    } catch (e: Exception) { Line(false, "Model unavailable", "${e.message} (keyword analyst will cover)") }
                    c.live.warmUpModel()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Ping the language model") }
            llm?.let { Text((when (it.ok) { true -> "✅ "; false -> "⚠ "; null -> "… " }) + it.label + (if (it.detail.isNotEmpty()) "\n   " + it.detail else ""), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            Button(onClick = { c.speaker.speak(if (s.language == "HI") "कवच तैयार है।" else "Kavach is ready.", sample.lang.tag) }, modifier = Modifier.fillMaxWidth()) { Text("Speak a test phrase") }
        }
    }
}
