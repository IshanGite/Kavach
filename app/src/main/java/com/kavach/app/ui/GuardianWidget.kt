package com.kavach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardBg = Color(0xFF0E1726)
private val TextOnDark = Color(0xFFF7F8FB)
private val Muted = Color(0xFFD5DDEA)
private val Track = Color(0xFF334155)
private val WidgetNavy = Color(0xFF0B3D91)
private val SafeGreen = Color(0xFF15803D)
private val WatchingBlue = Color(0xFF1D4ED8)
private val CautionOrange = Color(0xFFC2410C)
private val DangerRed = Color(0xFFB91C1C)
private val Shape = RoundedCornerShape(20.dp)

private fun tierColor(band: WidgetTierBand) = when (band) {
    WidgetTierBand.SAFE -> SafeGreen
    WidgetTierBand.WATCHING -> WatchingBlue
    WidgetTierBand.CAUTION -> CautionOrange
    WidgetTierBand.DANGER -> DangerRed
}

/**
 * The bubble and the expanded card. The window around this is only as big as the bubble or the card,
 * so taps outside it are not ours. Drag the bubble, or the card header, to move it; it snaps to an edge.
 */
@Composable
fun GuardianWidget(
    view: WidgetView,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onBubbleTap: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onCallHelpline: () -> Unit,
    onCallFamily: () -> Unit,
    onOpenHistory: () -> Unit,
    onCloseHistory: () -> Unit,
) {
    if (view.card == WidgetCard.BUBBLE) {
        Bubble(view, onDrag, onDragEnd, onBubbleTap)
    } else {
        ExpandedCard(view, onDrag, onDragEnd, onCollapse, onDismiss, onCallHelpline, onCallFamily, onOpenHistory, onCloseHistory)
    }
}

@Composable
private fun Bubble(
    view: WidgetView,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: () -> Unit,
) {
    val colour = if (view.tier == WidgetTierBand.SAFE) WidgetNavy else tierColor(view.tier)
    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Kavach, ${view.tierLabel}" + if (view.paymentBadge) ", payment app open" else "" }
            .dragAndTap(onTap, onDrag, onDragEnd),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colour)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ShieldGlyph(Color.White, Modifier.size(34.dp))
        }
        if (view.paymentBadge) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(WidgetNavy)
                    .border(1.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u20B9", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (view.alertCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(DangerRed)
                    .border(1.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (view.alertCount > 9) "9+" else view.alertCount.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ExpandedCard(
    view: WidgetView,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onCallHelpline: () -> Unit,
    onCallFamily: () -> Unit,
    onOpenHistory: () -> Unit,
    onCloseHistory: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(6.dp)
            .clip(Shape)
            .background(CardBg)
            .border(1.dp, Color.White.copy(alpha = 0.35f), Shape)
            .padding(12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .dragAndTap(onTap = {}, onDrag = onDrag, onDragEnd = onDragEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(tierColor(view.tier)),
                contentAlignment = Alignment.Center,
            ) { ShieldGlyph(Color.White, Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Text("Kavach", color = TextOnDark, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text(view.tierLabel, color = tierColor(view.tier), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (view.card) {
                WidgetCard.ALERT -> AlertBody(view)
                WidgetCard.HISTORY -> HistoryBody(view)
                else -> LiveBody(view)
            }
        }
        if (view.card != WidgetCard.HISTORY && view.alertCount > 0) {
            TextButtonLabel(onOpenHistory, "${view.recentLabel} (${view.alertCount})")
        }
        ActionRow(view, onCallHelpline, onCallFamily, onDismiss, onCollapse, onCloseHistory)
    }
}

@Composable
private fun LiveBody(view: WidgetView) {
    view.callerTitle?.let { title ->
        Text(title, color = TextOnDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        view.callerSubtitle?.let { Text(it, color = TextOnDark, fontSize = 16.sp, fontFamily = FontFamily.Monospace) }
    }
    val meta = listOfNotNull(
        view.callElapsedSec?.let { formatCallTimer(it) },
        view.languageLabel,
    )
    if (meta.isNotEmpty()) Text(meta.joinToString("   ·   "), color = Muted, fontSize = 14.sp)
    RiskMeter(view)
    if (view.transcript.isNotEmpty() || view.listeningHint != null) {
        Text(view.transcriptTitle, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        view.listeningHint?.let { Text(it, color = Muted, fontSize = 15.sp) }
        view.transcript.forEach { TranscriptLineText(it) }
    }
    if (view.reasons.isNotEmpty()) {
        Text(view.reasonsTitle, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        view.reasons.forEach { Text("• $it", color = TextOnDark, fontSize = 16.sp) }
    }
    view.footnote?.let { Text(it, color = Color(0xFFFDBA74), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun AlertBody(view: WidgetView) {
    Text(view.alertTitle.orEmpty(), color = DangerRed, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    view.alertSource?.let { Text(it, color = Muted, fontSize = 14.sp) }
    Text(view.whyTitle, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Text(view.alertWhy.orEmpty(), color = TextOnDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    view.alertExcerpt?.takeIf { it.isNotBlank() }?.let {
        Text("“$it”", color = TextOnDark, fontSize = 15.sp)
    }
    AdviceBlock(view)
    RiskMeter(view)
}

@Composable
private fun HistoryBody(view: WidgetView) {
    Text(view.recentLabel, color = TextOnDark, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    AdviceBlock(view)
    view.recentAlerts.forEach { alert ->
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF162033))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(alert.source, color = Muted, fontSize = 13.sp)
            Text(alert.why, color = TextOnDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (alert.excerpt.isNotBlank()) {
                Text(alert.excerpt, color = Muted, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AdviceBlock(view: WidgetView) {
    if (view.advice.isEmpty()) return
    Text(view.adviceTitle.orEmpty(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    view.advice.forEach { Text("• $it", color = TextOnDark, fontSize = 16.sp) }
}

@Composable
private fun RiskMeter(view: WidgetView) {
    val colour = tierColor(view.tier)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(view.paymentRiskLabel, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            view.scoreLine,
            color = TextOnDark,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            modifier = Modifier.semantics {
                contentDescription = view.scoreLine
            },
        )
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Track)) {
            val fraction = (view.risk.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
            if (fraction > 0f) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(colour))
            }
        }
    }
}

@Composable
private fun ActionRow(
    view: WidgetView,
    onCallHelpline: () -> Unit,
    onCallFamily: () -> Unit,
    onDismiss: () -> Unit,
    onCollapse: () -> Unit,
    onCloseHistory: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledAction(view.helplineLabel, SafeGreen, onCallHelpline, Modifier.weight(1f))
        if (view.familyLabel != null && view.card != WidgetCard.HISTORY) {
            FilledAction(view.familyLabel, WidgetNavy, onCallFamily, Modifier.weight(1f))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (view.card == WidgetCard.HISTORY) {
            OutlineAction(view.backLabel, onCloseHistory, Modifier.weight(1f))
        } else {
            OutlineAction(view.dismissLabel, onDismiss, Modifier.weight(1f))
        }
        OutlineAction(view.collapseLabel, onCollapse, Modifier.weight(1f))
    }
}

@Composable
private fun FilledAction(label: String, colour: Color, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colour, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable
private fun OutlineAction(label: String, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable
private fun TextButtonLabel(onClick: () -> Unit, label: String) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TranscriptLineText(line: HighlightedLine) {
    val styled = buildAnnotatedString {
        var pos = 0
        for (span in line.spans) {
            if (span.start > pos) append(line.text.substring(pos, span.start.coerceAtMost(line.text.length)))
            val end = span.endExclusive.coerceAtMost(line.text.length)
            if (end > span.start) {
                withStyle(SpanStyle(color = Color.White, background = DangerRed, fontWeight = FontWeight.Bold)) {
                    append(line.text.substring(span.start, end))
                }
            }
            pos = end
        }
        if (pos < line.text.length) append(line.text.substring(pos))
    }
    Text(styled, color = TextOnDark, fontSize = 16.sp, lineHeight = 22.sp)
}

@Composable
private fun ShieldGlyph(colour: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.04f)
            lineTo(w * 0.92f, h * 0.22f)
            lineTo(w * 0.92f, h * 0.52f)
            quadraticTo(w * 0.92f, h * 0.78f, w * 0.50f, h * 0.98f)
            quadraticTo(w * 0.08f, h * 0.78f, w * 0.08f, h * 0.52f)
            lineTo(w * 0.08f, h * 0.22f)
            close()
        }
        drawPath(path, colour)
    }
}

internal fun formatCallTimer(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun Modifier.dragAndTap(
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = composed {
    val host = LocalView.current
    pointerInput(Unit) {
        // The window moves under the finger, so window-local deltas jitter. Track the pointer in screen coordinates.
        val loc = IntArray(2)
        fun screenPos(local: Offset): Offset {
            host.getLocationOnScreen(loc)
            return Offset(loc[0] + local.x, loc[1] + local.y)
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            val start = screenPos(down.position)
            var last = start
            var pastSlop = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (pastSlop) onDragEnd() else onTap()
                    break
                }
                val now = screenPos(change.position)
                if (!pastSlop && (now - start).getDistance() > slop) pastSlop = true
                if (pastSlop) {
                    val dx = now.x - last.x
                    val dy = now.y - last.y
                    if (dx != 0f || dy != 0f) {
                        change.consume()
                        onDrag(dx, dy)
                    }
                }
                last = now
            }
        }
    }
}
