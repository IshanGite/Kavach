package com.kavach.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand navy. White text on this is about 10:1. */
val Navy = Color(0xFF0B3D91)

/** Calm teal. White text on this is about 5.3:1. */
val Safe = Color(0xFF0E7A62)

/** Deep amber. White text on this is about 6.8:1, and so is this colour on white. */
val Caution = Color(0xFF8C4A08)

/** Danger red. White text on this is about 6.5:1. */
val Alarm = Color(0xFFB3261E)

/** Tier-1 "watching" blue. White text on this is about 7:1. */
val Watch = Color(0xFF1558B0)

private val SafeOnDark = Color(0xFF7DDBC4)
private val WatchOnDark = Color(0xFFADC6FF)
private val CautionOnDark = Color(0xFFFFC27A)
private val AlarmOnDark = Color(0xFFFFB4AB)

fun tierFill(level: Int): Color = when (level.coerceIn(0, 3)) {
    0 -> Safe
    1 -> Watch
    2 -> Caution
    else -> Alarm
}

fun tierLabel(level: Int): String = when (level.coerceIn(0, 3)) {
    0 -> "Safe"
    1 -> "Watching"
    2 -> "Caution"
    else -> "Danger"
}

/** Saturated on a light surface, light on a dark surface, so the gauge stays readable. */
@Composable
fun tierAccent(level: Int): Color = if (isSystemInDarkTheme()) {
    when (level.coerceIn(0, 3)) {
        0 -> SafeOnDark
        1 -> WatchOnDark
        2 -> CautionOnDark
        else -> AlarmOnDark
    }
} else tierFill(level)

@Composable
fun safeOnSurface(): Color = if (isSystemInDarkTheme()) SafeOnDark else Safe

@Composable
fun cautionOnSurface(): Color = if (isSystemInDarkTheme()) CautionOnDark else Caution

@Composable
fun alarmOnSurface(): Color = if (isSystemInDarkTheme()) AlarmOnDark else Alarm

private val KavachTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Safe,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4F3EA),
    onSecondaryContainer = Color(0xFF05382C),
    tertiary = Caution,
    onTertiary = Color.White,
    error = Alarm,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF3F6FB),
    onBackground = Color(0xFF102033),
    surface = Color.White,
    onSurface = Color(0xFF102033),
    surfaceVariant = Color(0xFFE6EDF6),
    onSurfaceVariant = Color(0xFF3E4A5C),
    outline = Color(0xFF5C6B82),
    outlineVariant = Color(0xFFC5D0DE),
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = WatchOnDark,
    onPrimary = Color(0xFF002E6A),
    primaryContainer = Navy,
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = SafeOnDark,
    onSecondary = Color(0xFF05382C),
    secondaryContainer = Color(0xFF0C4F42),
    onSecondaryContainer = Color(0xFFD7F6ED),
    tertiary = CautionOnDark,
    onTertiary = Color(0xFF4A2800),
    error = AlarmOnDark,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EEF8),
    surface = Color(0xFF162033),
    onSurface = Color(0xFFE6EEF8),
    surfaceVariant = Color(0xFF1E2C44),
    onSurfaceVariant = Color(0xFFC5D0E0),
    outline = Color(0xFF8B98AD),
    outlineVariant = Color(0xFF3D4F6B),
    surfaceTint = Color.Transparent,
)

@Composable
fun KavachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = KavachTypography,
        content = content,
    )
}

@Composable
fun TierChip(level: Int, modifier: Modifier = Modifier) {
    val fill by animateColorAsState(tierFill(level), tween(450), label = "tierChip")
    Surface(modifier = modifier, color = fill, shape = RoundedCornerShape(8.dp)) {
        Text(
            tierLabel(level),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

/** One readiness row. [ok] null means "not checked", which pre-flight uses for optional items. */
@Composable
fun StatusLine(ok: Boolean?, label: String, detail: String? = null) {
    val tint = when (ok) {
        true -> safeOnSurface()
        false -> alarmOnSurface()
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (ok) {
        true -> Icons.Filled.CheckCircle
        false -> Icons.Filled.Error
        null -> Icons.Filled.Info
    }
    val state = when (ok) {
        true -> "Ready"
        false -> "Needs attention"
        null -> "Not checked"
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = if (detail.isNullOrBlank()) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = state, tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (ok == false) FontWeight.SemiBold else FontWeight.Normal,
                color = if (ok == false) tint else MaterialTheme.colorScheme.onSurface,
            )
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
