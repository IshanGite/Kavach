package com.kavach.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.core.intervention.InterventionPlan
import com.kavach.core.intervention.Lang
import com.kavach.core.intervention.TrustedContact
import com.kavach.core.risk.Tier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private fun t(lang: Lang, en: String, hi: String) = if (lang == Lang.HI) hi else en

/** Tier 1: a small banner over the call or pay screen. Does not take focus, so the payment app stays usable. */
@Composable
fun NudgeBanner(plan: InterventionPlan, onCall: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = Caution,
        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                Text(
                    plan.headline,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 26.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            plan.reasons.forEach { Text("• $it", color = Color.White, fontSize = 16.sp, lineHeight = 22.sp) }
            plan.mythBuster?.let { Text(it, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (plan.callTrusted != null) {
                    Button(
                        onClick = onCall,
                        modifier = Modifier.weight(1.4f).heightIn(min = 56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Caution),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            plan.callTrustedLabel.orEmpty(),
                            modifier = Modifier.weight(1f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = (if (plan.callTrusted != null) Modifier.weight(0.8f) else Modifier.fillMaxWidth()).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(2.dp, Color.White),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(t(plan.lang, "Dismiss", "बंद करें"), fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            if (plan.askSafeWord) {
                Text(
                    t(plan.lang, "Ask them the family safe word.", "उनसे परिवार का सीक्रेट शब्द पूछिए।"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

/**
 * Tier 2/3: full-screen pause. Specific reasons, a cooling-off timer, the three questions, a big "call your family"
 * button and, beside it, a one-tap cybercrime helpline (1930) button. The user can ALWAYS proceed once the timer ends:
 * Kavach never blocks a legitimate payment, it only breaks the scammer's grip.
 */
@Composable
fun PauseScreen(
    plan: InterventionPlan,
    pauseUntilMs: StateFlow<Long?>,
    now: () -> Long,
    onCall: () -> Unit,
    onCallHelpline: () -> Unit,
    onAnswered: (yesCount: Int) -> Unit,
    onProceed: () -> Unit,
) {
    val bg = if (plan.tier == Tier.PAUSE_GUARDIAN) Alarm else Caution
    val screenColor by animateColorAsState(bg, tween(450), label = "pauseBg")
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
    val secs = ((remainingMs + 999) / 1000).toInt()
    val totalMs = (plan.cooldownSeconds.coerceAtLeast(1) * 1000L).toFloat()
    val fraction = (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().background(screenColor)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(plan.headline, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 40.sp)
            TimerRing(fraction, "%d:%02d".format(secs / 60, secs % 60))

            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Caution, modifier = Modifier.size(20.dp))
                        Text(t(lang, "What Kavach noticed", "Kavach ने क्या देखा"), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Navy)
                    }
                    plan.reasons.forEach { Text("• $it", fontSize = 20.sp, lineHeight = 28.sp, color = Color(0xFF102033)) }
                }
            }
            plan.mythBuster?.let { Text(it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp) }

            if (plan.callTrusted != null || plan.callHelpline != null) {
                val sideBySide = plan.callTrusted != null && plan.callHelpline != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (plan.callTrusted != null) {
                        AlertButton(
                            label = plan.callTrustedLabel.orEmpty(),
                            onClick = onCall,
                            container = Safe,
                            content = Color.White,
                            weight = 1f,
                            minHeight = if (sideBySide) 96.dp else 72.dp,
                            fontSize = if (sideBySide) 18.sp else 24.sp,
                            lineHeight = if (sideBySide) 22.sp else 28.sp,
                        )
                    }
                    if (plan.callHelpline != null) {
                        AlertButton(
                            label = plan.callHelplineLabel.orEmpty(),
                            onClick = onCallHelpline,
                            container = Color.White,
                            content = Navy,
                            weight = if (sideBySide) 1.35f else 1f,
                            minHeight = if (sideBySide) 96.dp else 72.dp,
                            fontSize = if (sideBySide) 16.sp else 22.sp,
                            lineHeight = if (sideBySide) 20.sp else 26.sp,
                        )
                    }
                }
                Text(
                    t(lang, "Hang up on the caller first, then call.", "पहले कॉलर का फ़ोन काटिए, फिर कॉल कीजिए।"),
                    color = Color.White,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                )
            }
            plan.callbackVerification?.let { Text(it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp) }
            if (plan.askSafeWord) {
                Text(
                    t(lang, "Ask for the family safe word. A cloned voice cannot know it.", "परिवार का सीक्रेट शब्द पूछिए। क्लोन की गई आवाज़ उसे नहीं जानती।"),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 24.sp,
                )
            }

            if (plan.questions.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.30f)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        plan.questions.forEachIndexed { i, q ->
                            Text("${i + 1}. $q", color = Color.White, fontSize = 18.sp, lineHeight = 24.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((label, v) in listOf(t(lang, "Yes", "हाँ") to true, t(lang, "No", "नहीं") to false)) {
                                    val sel = answers[i] == v
                                    OutlinedButton(
                                        onClick = { answers[i] = v; onAnswered(answers.values.count { it }) },
                                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(2.dp, Color.White),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (sel) Color.White else Color.Transparent,
                                            contentColor = if (sel) screenColor else Color.White,
                                        ),
                                    ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }
                if (yes > 0) Text(plan.ifAnyYes, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp)
            }

            val canProceed = remainingMs <= 0
            Button(
                onClick = onProceed,
                enabled = canProceed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Navy,
                    disabledContainerColor = Color.White.copy(alpha = 0.16f),
                    disabledContentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    if (canProceed) Icons.Filled.Check else Icons.Filled.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (remainingMs > 0) t(lang, "You can continue in ${secs / 60}:${"%02d".format(secs % 60)}", "आप ${secs / 60}:${"%02d".format(secs % 60)} बाद आगे बढ़ सकते हैं")
                    else t(lang, "I understand the risk, continue", "मैं जोखिम समझता हूँ, आगे बढ़ें"),
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimerRing(fraction: Float, label: String) {
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "cooldown")
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2
                val topLeft = Offset(inset, inset)
                val sz = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = Color.White.copy(alpha = 0.35f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = sz,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (anim > 0f) {
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * anim,
                        useCenter = false,
                        topLeft = topLeft,
                        size = sz,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        }
    }
}

@Composable
private fun RowScope.AlertButton(
    label: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    weight: Float,
    minHeight: Dp,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(weight).heightIn(min = minHeight),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                fontSize = fontSize,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                lineHeight = lineHeight,
            )
        }
    }
}

@Preview(name = "Pause", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PauseScreenPreview() {
    KavachTheme {
        PauseScreen(
            plan = InterventionPlan(
                tier = Tier.PAUSE_GUARDIAN,
                lang = Lang.EN,
                headline = "STOP. This looks like a scam in progress.",
                reasons = listOf(
                    "The caller claimed to be a CBI officer and told you not to tell anyone.",
                    "You opened PhonePe while this call is still live.",
                    "The account you are paying was read out to you on this call.",
                ),
                mythBuster = "CBI and police never arrest anyone over a video call and never ask for money.",
                callTrusted = TrustedContact("Rahul", "100", "son"),
                callTrustedLabel = "Call Rahul now",
                askSafeWord = false,
                callbackVerification = null,
                cooldownSeconds = 90,
                questions = listOf(
                    "Is the person on the call telling you to keep this secret or not to tell your family?",
                    "Did they say you will be arrested, or your account frozen, if you don't pay now?",
                    "Are they asking you to stay on the call while you pay?",
                ),
                ifAnyYes = "This is exactly how scammers work. Government agencies never do this. Hang up and call your family on their saved number.",
                spokenWarning = null,
                callHelpline = "1930",
                callHelplineLabel = "Call 1930 (cybercrime helpline)",
            ),
            pauseUntilMs = MutableStateFlow<Long?>(90_000L),
            now = { 0L },
            onCall = {},
            onCallHelpline = {},
            onAnswered = {},
            onProceed = {},
        )
    }
}
