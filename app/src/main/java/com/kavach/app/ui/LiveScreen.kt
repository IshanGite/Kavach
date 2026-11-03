package com.kavach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.container
import com.kavach.core.model.TranscriptChunk
import com.kavach.core.risk.RuleCode
import com.kavach.core.risk.Tier
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0B1220)
private val Panel = Color(0xFF16213A)
private val Green = Color(0xFF2EBD6B)
private val Amber = Color(0xFFF2A33A)
private val Red = Color(0xFFE5484D)
private val Dim = Color(0xFF8FA0BF)

private fun tierColor(t: Int) = when (t) { 0 -> Green; 1 -> Amber; else -> Red }

private fun chip(code: RuleCode): String = when (code) {
    RuleCode.UNKNOWN_LIVE_CALL -> "Unknown caller live"
    RuleCode.LONG_CALL -> "Long call"
    RuleCode.UNKNOWN_VOIP_OR_VIDEO -> "Unknown video/VoIP"
    RuleCode.PAYMENT_APP_DURING_CALL -> "Payment app opened mid-call"
    RuleCode.NEW_PAYEE -> "New payee"
    RuleCode.AMOUNT_ABOVE_USUAL -> "Amount above usual"
    RuleCode.ODD_HOUR -> "Odd hour"
    RuleCode.REMOTE_ACCESS_ACTIVE -> "Remote-access app"
    RuleCode.OTP_OR_COLLECT_DURING_CALL -> "OTP / collect request"
    RuleCode.SYNTHETIC_VOICE -> "Synthetic voice"
    RuleCode.NUMBER_ON_SCAM_LIST -> "Known scam number"
    RuleCode.RELATED_SMS_FLAGGED -> "Related scam SMS"
    RuleCode.REPEATED_NEW_PAYEES -> "Several new payees"
    RuleCode.ESCALATING_NEW_PAYEE_AMOUNTS -> "Escalating amounts"
}

/**
 * The judges' view (mirror it with scrcpy): risk gauge with the 35 / 60 / 80 tier marks, what Kavach is hearing with the evidence
 * highlighted, why the score is what it is (rule chips with points), and what it decided. Follows whichever engine is active:
 * the real sensors, or a running simulation.
 */
@Composable
fun LiveScreen() {
    val c = LocalContext.current.container()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(350); tick++ } }
    val engine = remember(tick / 6) { c.reportEngine() }
    val d by engine.latest.collectAsState()
    val log by engine.log.collectAsState()
    val llm by engine.llmStatus.collectAsState()
    val session = remember(tick) { engine.orchestrator.session }

    val r = d?.result
    val tier = d?.tier?.level ?: 0
    val risk = r?.risk ?: 0
    val col = tierColor(tier)

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("KAVACH", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("${engine.label.uppercase()}  ·  ${d?.state ?: "IDLE"}", color = Dim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }

        // Gauge
        Box(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(240.dp)) {
                val stroke = 26.dp.toPx()
                val sz = Size(size.width - stroke, size.height - stroke)
                val tl = Offset(stroke / 2, stroke / 2)
                drawArc(Color(0xFF2A3A5C), 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(col, 135f, 270f * (risk / 100f).coerceIn(0f, 1f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                // tier marks at 35 / 60 / 80
                listOf(35, 60, 80).forEach { m ->
                    val a = Math.toRadians((135 + 270 * m / 100.0))
                    val rIn = size.width / 2 - stroke - 2.dp.toPx(); val rOut = size.width / 2 + 2.dp.toPx() - 4.dp.toPx()
                    val cx = size.width / 2; val cy = size.height / 2
                    drawLine(Color.White, Offset((cx + rIn * Math.cos(a)).toFloat(), (cy + rIn * Math.sin(a)).toFloat()), Offset((cx + rOut * Math.cos(a)).toFloat(), (cy + rOut * Math.sin(a)).toFloat()), 3.dp.toPx())
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$risk", color = col, fontSize = 72.sp, fontWeight = FontWeight.ExtraBold)
                Text(listOf("NORMAL", "CAUTION", "PAUSE", "PAUSE + GUARDIAN")[tier.coerceIn(0, 3)], color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("context ${r?.contextScore ?: 0}   language ${r?.languageScore ?: "–"}", color = Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // Why
        val rules = r?.rules?.sortedByDescending { it.points }.orEmpty()
        if (rules.isNotEmpty() || r?.hardOverride != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("WHY", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                r?.hardOverride?.let {
                    Text("⚠ HARD OVERRIDE: " + when (it) { com.kavach.core.risk.HardOverride.PAYEE_FROM_CALL -> "payee was dictated on the call" else -> "remote-access app + payment" },
                        color = Color.White, modifier = Modifier.background(Red, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                rules.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { h ->
                            Text("${chip(h.code)}  +${h.points}", color = Color.White, fontSize = 14.sp,
                                modifier = Modifier.weight(1f).background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // What Kavach hears
        val lines = session.transcript.takeLast(8)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("WHAT KAVACH HEARS", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (lines.isEmpty()) Text("No transcript yet (audio option D uses call and screen context only).", color = Dim, fontSize = 14.sp)
            lines.forEach { ch -> Text(highlight(ch, session.evidenceQuotes), fontSize = 17.sp, color = Color.White, lineHeight = 22.sp) }
            val who = listOfNotNull(session.claimedIdentity.takeIf { it.name != "NONE" }?.name?.lowercase()?.replace('_', ' '),
                session.scamTypeSeen.takeIf { it.name != "NONE" }?.name?.lowercase()?.replace('_', ' '))
            if (who.isNotEmpty()) Text("Reads as: ${who.joinToString(" · ")}", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        // What it decided
        val decisions = log.filter { it.kind == "decision" && "=>" in it.text }.takeLast(4)
        if (decisions.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("DECISIONS", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            decisions.forEach { Text(it.text.substringAfter("=> "), color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace) }
        }
        r?.let {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("BANK-SIDE SIGNAL (what a bank or PSP could receive, no personal data)", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(com.kavach.core.risk.BankSignal.toJson(it, d?.atMs ?: 0L), color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp))
            }
        }
        Text("Language model: $llm", color = Dim, fontSize = 12.sp)
        Text("Everything above is computed on this phone.", color = Dim, fontSize = 12.sp)
    }
}

private fun highlight(ch: TranscriptChunk, quotes: List<String>) = buildAnnotatedString {
    val text = ch.text
    val lower = text.lowercase()
    val ranges = quotes.mapNotNull { q -> lower.indexOf(q.lowercase()).takeIf { it >= 0 }?.let { it until it + q.length } }.sortedBy { it.first }
    var pos = 0
    for (rg in ranges) {
        if (rg.first < pos) continue
        append(text.substring(pos, rg.first))
        withStyle(SpanStyle(background = Red.copy(alpha = 0.75f), fontWeight = FontWeight.Bold)) { append(text.substring(rg.first, rg.last + 1)) }
        pos = rg.last + 1
    }
    append(text.substring(pos))
}
