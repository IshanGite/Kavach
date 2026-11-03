package com.kavach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.core.intervention.InterventionPlan
import com.kavach.core.intervention.Lang
import com.kavach.core.risk.Tier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val Navy = Color(0xFF0B3D91)
private val Amber = Color(0xFFB45F06)
private val Alarm = Color(0xFFB3261E)
private val Safe = Color(0xFF1B7F3B)

@Composable
fun KavachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Navy, secondary = Safe, error = Alarm),
        content = content,
    )
}

private fun t(lang: Lang, en: String, hi: String) = if (lang == Lang.HI) hi else en

/** Tier 1: a small banner over the call or pay screen. Does not take focus, so the payment app stays usable. */
@Composable
fun NudgeBanner(plan: InterventionPlan, onCall: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = Amber, modifier = Modifier.fillMaxWidth().statusBarsPadding(), shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(plan.headline, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            plan.reasons.forEach { Text("• $it", color = Color.White, fontSize = 16.sp) }
            plan.mythBuster?.let { Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (plan.callTrusted != null) {
                    Button(onClick = onCall, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Amber)) {
                        Text(plan.callTrustedLabel.orEmpty(), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onDismiss) { Text(t(plan.lang, "Dismiss", "बंद करें"), color = Color.White, fontSize = 16.sp) }
            }
            if (plan.askSafeWord) Text(t(plan.lang, "Ask them the family safe word.", "उनसे परिवार का सीक्रेट शब्द पूछिए।"), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Tier 2/3: full-screen pause. Specific reasons, a cooling-off timer, the three questions, a big "call your family" button.
 * The user can ALWAYS proceed once the timer ends: Kavach never blocks a legitimate payment, it only breaks the scammer's grip.
 */
@Composable
fun PauseScreen(
    plan: InterventionPlan,
    pauseUntilMs: StateFlow<Long?>,
    now: () -> Long,
    onCall: () -> Unit,
    onAnswered: (yesCount: Int) -> Unit,
    onProceed: () -> Unit,
) {
    val bg = if (plan.tier == Tier.PAUSE_GUARDIAN) Alarm else Amber
    val lang = plan.lang
    val until by pauseUntilMs.collectAsState()
    // Remaining time re-reads the (possibly guardian-extended) deadline every 250 ms.
    var remainingMs by remember { mutableLongStateOf(plan.cooldownSeconds * 1000L) }
    LaunchedEffect(until) {
        while (true) {
            remainingMs = ((until ?: 0L) - now()).coerceAtLeast(0)
            delay(250)
        }
    }
    val answers = remember { mutableStateMapOf<Int, Boolean>() }
    val yes = answers.values.count { it }

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(plan.headline, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp)

            Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(lang, "What Kavach noticed", "Kavach ने क्या देखा"), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.DarkGray)
                    plan.reasons.forEach { Text("• $it", fontSize = 19.sp, color = Color.Black) }
                }
            }
            plan.mythBuster?.let { Text(it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }

            if (plan.callTrusted != null) {
                Button(
                    onClick = onCall, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Safe, contentColor = Color.White), shape = RoundedCornerShape(16.dp),
                ) { Text(plan.callTrustedLabel.orEmpty(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold) }
                Text(t(lang, "Hang up on the caller first, then call.", "पहले कॉलर का फ़ोन काटिए, फिर कॉल कीजिए।"), color = Color.White, fontSize = 15.sp)
            }
            plan.callbackVerification?.let { Text(it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            if (plan.askSafeWord) Text(t(lang, "Ask for the family safe word. A cloned voice cannot know it.", "परिवार का सीक्रेट शब्द पूछिए। क्लोन की गई आवाज़ उसे नहीं जानती।"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            if (plan.questions.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.25f)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        plan.questions.forEachIndexed { i, q ->
                            Text("${i + 1}. $q", color = Color.White, fontSize = 17.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                for ((label, v) in listOf(t(lang, "Yes", "हाँ") to true, t(lang, "No", "नहीं") to false)) {
                                    val sel = answers[i] == v
                                    OutlinedButton(
                                        onClick = { answers[i] = v; onAnswered(answers.values.count { it }) },
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (sel) Color.White else Color.Transparent, contentColor = if (sel) bg else Color.White),
                                    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }
                if (yes > 0) Text(plan.ifAnyYes, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }

            val secs = ((remainingMs + 999) / 1000).toInt()
            OutlinedButton(
                onClick = onProceed, enabled = remainingMs <= 0, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.55f)),
            ) {
                Text(
                    if (remainingMs > 0) t(lang, "You can continue in ${secs / 60}:${"%02d".format(secs % 60)}", "आप ${secs / 60}:${"%02d".format(secs % 60)} बाद आगे बढ़ सकते हैं")
                    else t(lang, "I understand the risk, continue", "मैं जोखिम समझता हूँ, आगे बढ़ें"),
                    fontSize = 17.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
