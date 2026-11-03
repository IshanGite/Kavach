package com.kavach.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.container
import com.kavach.app.engine.KavachEngine
import com.kavach.core.model.PaymentDismissed
import com.kavach.core.model.UserProceeded
import com.kavach.core.orchestrator.Decision
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private enum class PayStep { HOME, AMOUNT, WAITING, HELD, PIN, SUCCESS, HISTORY, BALANCE, QR }

private data class InFlight(val pay: ResolvedPay, val amountAt: Long, val before: Decision?)

private val PayHeaderBrush = Brush.linearGradient(
    colors = listOf(Color(0xFF041530), Color(0xFF0B3D91), Color(0xFF0A3A4A)),
)

private val PayeePalette = listOf(
    Color(0xFF0E7A62),
    Color(0xFF1558B0),
    Color(0xFF5E35B1),
)

private val NewPayeeColor = Color(0xFF8C4A08)

/** Obvious fake figure for the Bank balance action. */
private const val FAKE_BALANCE_RUPEES = 24_680.5

/**
 * Mock UPI flow wired to the live engine (`c.live`), the same one Debug and the sensors use.
 * Pay emits OPENED then AMOUNT_ENTERED. Tier 2+ shows the standard pause overlay before this PIN.
 * The keypad keeps a length only. Digits are never stored.
 */
@Composable
fun KavachPayScreen() {
    val ctx = LocalContext.current
    val live = ctx.container().live
    val overlay = ctx.container().overlay
    var step by remember { mutableStateOf(PayStep.HOME) }
    val stepNow = rememberUpdatedState(step)
    var form by remember { mutableStateOf(KavachPay.blankForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var flight by remember { mutableStateOf<InFlight?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var heldTier by remember { mutableIntStateOf(0) }
    var pinLength by remember { mutableIntStateOf(0) }
    var overlayMissing by remember { mutableStateOf(false) }
    var successAt by remember { mutableLongStateOf(0L) }
    val history by KavachPayLedger.items.collectAsState()

    DisposableEffect(live) {
        onDispose {
            when (stepNow.value) {
                PayStep.WAITING, PayStep.HELD, PayStep.PIN -> live.emit(PaymentDismissed(live.now()))
                PayStep.HOME, PayStep.AMOUNT, PayStep.SUCCESS, PayStep.HISTORY, PayStep.BALANCE, PayStep.QR -> Unit
            }
        }
    }

    LaunchedEffect(generation) {
        val current = flight ?: return@LaunchedEffect
        if (generation == 0 || step != PayStep.WAITING) return@LaunchedEffect
        val decision = awaitAmountDecision(live, current.amountAt, current.before)
        if (step != PayStep.WAITING) return@LaunchedEffect
        if (decision == null) {
            live.emit(PaymentDismissed(live.now()))
            error = "The live engine did not answer. Nothing was sent."
            flight = null
            step = PayStep.AMOUNT
            return@LaunchedEffect
        }
        heldTier = decision.tier.level
        step = if (KavachPay.pinWaitsForPause(decision.tier.level)) PayStep.HELD else PayStep.PIN
    }

    LaunchedEffect(generation, step) {
        if (step != PayStep.HELD) return@LaunchedEffect
        val after = flight?.amountAt ?: return@LaunchedEffect
        live.log.first { log -> log.any { it.kind == "event" && it.text == "UserProceeded" && it.atMs >= after } }
        if (step == PayStep.HELD) step = PayStep.PIN
    }

    LaunchedEffect(generation, step) {
        if (step != PayStep.HELD) {
            overlayMissing = false
            return@LaunchedEffect
        }
        while (true) {
            delay(300)
            overlayMissing = !overlay.isShowing()
        }
    }

    LaunchedEffect(generation, step) {
        val current = flight ?: return@LaunchedEffect
        if (step != PayStep.PIN) return@LaunchedEffect
        pinLength = 0
        live.emit(KavachPay.pinIntent(current.pay, live.now()))
    }

    fun applyRole(role: RolePlay) {
        form = KavachPay.rolePlayForm(role)
        error = null
        status = when (role) {
            RolePlay.NORMAL -> "Filled Rahul, ₹500. Cleared any demo call on the live engine."
            RolePlay.COERCED -> "Filled a new payee and ₹50,000. Injected an unknown call and a scam line. Tap Pay."
        }
        pinLength = 0
        val at = live.now()
        KavachPay.contextEvents(role, at).forEach { live.emit(it) }
        step = PayStep.AMOUNT
    }

    fun openAmount(next: PayForm = form) {
        val edited = next != form
        form = next
        error = null
        if (edited) status = null
        step = PayStep.AMOUNT
    }

    fun editForm(next: PayForm) {
        if (next == form) return
        form = next
        status = null
    }

    fun startPay() {
        val pay = KavachPay.resolve(form)
        if (pay == null) {
            error = KavachPay.invalidReason(form)
            return
        }
        error = null
        pinLength = 0
        val before = live.latest.value
        val at = live.now()
        live.emit(PaymentDismissed(at))
        val intents = KavachPay.paymentIntents(pay, at + 1)
        flight = InFlight(pay, intents.last().atMs, before)
        intents.forEach { live.emit(it) }
        step = PayStep.WAITING
        generation++
    }

    fun finishPayment() {
        val current = flight ?: return
        if (!KavachPay.isAcceptablePinLength(pinLength)) {
            error = "Enter 4 to 6 digits."
            return
        }
        pinLength = 0
        error = null
        status = null
        val at = live.now()
        successAt = at
        KavachPayLedger.add(
            SimulatedPayment(current.pay.label, current.pay.upiId, current.pay.amountRupees, current.pay.note, at),
        )
        // Only saved contacts teach the baseline. A new payee (the coerced demo account) must stay "new" so a judge can re-run it.
        if (KavachPay.savedPayees.any { it.upiId == current.pay.upiId }) {
            live.scope.launch {
                live.orchestrator.recordCompletedPayment(current.pay.upiId, current.pay.amountRupees, at)
            }
        }
        live.emit(PaymentDismissed(at))
        step = PayStep.SUCCESS
    }

    fun cancelPayment() {
        pinLength = 0
        live.emit(PaymentDismissed(live.now()))
        flight = null
        step = PayStep.AMOUNT
    }

    Column(Modifier.fillMaxSize()) {
        DemoRibbon()
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (step) {
                PayStep.HOME -> HomeBody(
                    form = form,
                    status = status,
                    onPayContact = { openAmount() },
                    onScan = { step = PayStep.QR },
                    onBalance = { step = PayStep.BALANCE },
                    onHistory = { step = PayStep.HISTORY },
                    onSelectPayee = { id -> openAmount(form.copy(selection = id)) },
                    onRole = ::applyRole,
                )
                PayStep.AMOUNT -> AmountBody(
                    form = form,
                    error = error,
                    status = status,
                    onForm = ::editForm,
                    onBack = { step = PayStep.HOME },
                    onPay = ::startPay,
                )
                PayStep.WAITING -> WaitingBody(flight)
                PayStep.HELD -> HeldBody(
                    flight = flight,
                    tier = heldTier,
                    overlayMissing = overlayMissing,
                    onContinue = { live.emit(UserProceeded(live.now())) },
                    onCancel = ::cancelPayment,
                )
                PayStep.PIN -> PinBody(
                    flight = flight,
                    pinLength = pinLength,
                    error = error,
                    onPress = { if (pinLength < 6) pinLength++ },
                    onBackspace = { if (pinLength > 0) pinLength--; error = null },
                    onOk = ::finishPayment,
                    onCancel = ::cancelPayment,
                )
                PayStep.SUCCESS -> SuccessBody(
                    pay = flight?.pay,
                    atMs = successAt,
                    onNew = { flight = null; step = PayStep.HOME },
                )
                PayStep.HISTORY -> HistoryBody(history = history, onBack = { step = PayStep.HOME })
                PayStep.BALANCE -> BalanceBody(onBack = { step = PayStep.HOME })
                PayStep.QR -> QrBody(onBack = { step = PayStep.HOME })
            }
        }
    }
}

@Composable
private fun DemoRibbon() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Alarm)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            KavachPay.DEMO_BANNER,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HomeBody(
    form: PayForm,
    status: String?,
    onPayContact: () -> Unit,
    onScan: () -> Unit,
    onBalance: () -> Unit,
    onHistory: () -> Unit,
    onSelectPayee: (String) -> Unit,
    onRole: (RolePlay) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(PayHeaderBrush)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(Color.White.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("₹", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Kavach Pay",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                    )
                    Text(
                        "Simulated UPI · ${KavachPay.PACKAGE_NAME}",
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                QuickAction("Pay contact", Icons.Filled.Person, onPayContact)
                QuickAction("Scan QR", Icons.Filled.QrCodeScanner, onScan)
                QuickAction("Bank balance", Icons.Filled.AccountBalance, onBalance)
                QuickAction("History", Icons.Filled.History, onHistory)
            }
        }
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PayCard {
                Text("Recent", style = MaterialTheme.typography.titleMedium)
                PayeeRow(selection = form.selection, onSelect = onSelectPayee)
            }
            JudgeRolePlay(status = status, onRole = onRole)
        }
    }
}

@Composable
private fun RowScope.QuickAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {}
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(Color.White.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun JudgeRolePlay(status: String?, onRole: (RolePlay) -> Unit) {
    var notesOpen by remember { mutableStateOf(false) }
    PayCard {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = { notesOpen = !notesOpen }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Judge role-play", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (notesOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (notesOpen) "Hide role-play notes" else "Show role-play notes",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            "Coerced can text a saved guardian, because the live engine does that at tier 3.",
            style = MaterialTheme.typography.bodyMedium,
        )
        status?.let { StatusNote(it) }
        Button(onClick = { onRole(RolePlay.NORMAL) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Role-play: normal")
        }
        Button(onClick = { onRole(RolePlay.COERCED) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Role-play: coerced")
        }
        AnimatedVisibility(
            visible = notesOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                "Fills this form and injects events into the live engine, not the Simulate engine.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AmountBody(
    form: PayForm,
    error: String?,
    status: String?,
    onForm: (PayForm) -> Unit,
    onBack: () -> Unit,
    onPay: () -> Unit,
) {
    val who = KavachPay.savedPayees.firstOrNull { it.id == form.selection }?.label ?: "New payee"
    Column(Modifier.fillMaxSize().background(PayHeaderBrush)) {
        Text(
            "Paying $who",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .width(48.dp)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                    )
                }
                PayBackButton(onBack)
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PayeeRow(selection = form.selection, onSelect = { onForm(form.copy(selection = it)) })
                    val selected = KavachPay.savedPayees.firstOrNull { it.id == form.selection }
                    if (selected != null) {
                        Text(
                            selected.upiId,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = form.newUpiId,
                            onValueChange = { onForm(form.copy(newUpiId = it)) },
                            label = { Text("UPI id") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                    }
                    AmountField(form.amountText, onValue = { onForm(form.copy(amountText = sanitizeAmount(it))) }, onDone = onPay)
                    OutlinedTextField(
                        value = form.note,
                        onValueChange = { onForm(form.copy(note = it)) },
                        label = { Text("Note") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    status?.let { StatusNote(it) }
                    error?.let { ErrorLine(it) }
                    Button(onClick = onPay, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("Pay", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountField(amountText: String, onValue: (String) -> Unit, onDone: () -> Unit) {
    val empty = amountText.isEmpty()
    val long = amountText.length > 8
    val fontSize = if (long) 34.sp else 48.sp
    val glyph = if (long) 22 else 30
    val fieldWidth = (amountText.length.coerceAtLeast(1) * glyph + 32).coerceIn(64, 300).dp
    val color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Amount", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("₹", color = color, fontSize = if (long) 28.sp else 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.Center) {
                if (empty) {
                    Text(
                        "0",
                        color = color,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                BasicTextField(
                    value = amountText,
                    onValueChange = onValue,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (empty) Color.Transparent else color,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.width(fieldWidth).heightIn(min = 56.dp),
                )
            }
        }
        Box(
            Modifier
                .width(96.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
        )
        val parsed = KavachPay.parseAmountRupees(amountText)
        if (parsed != null) {
            val pretty = KavachPay.formatRupees(parsed)
            if (pretty != "₹$amountText") {
                Text(pretty, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WaitingBody(flight: InFlight?) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        flight?.let { PaySummary(it.pay, emphasize = true) }
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "Checking this payment with the live engine.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HeldBody(
    flight: InFlight?,
    tier: Int,
    overlayMissing: Boolean,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        flight?.let { PaySummary(it.pay, emphasize = true) }
        Text("Paused before the PIN", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tier $tier. The pause overlay is the same one Kavach draws over a real UPI app. Continue there after the timer, and this PIN screen opens.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (overlayMissing) {
            Text(
                "The overlay is not on screen. Enable the accessibility service or Display over other apps from Setup. You can still continue.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Continue to PIN") }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Cancel") }
    }
}

@Composable
private fun PinBody(
    flight: InFlight?,
    pinLength: Int,
    error: String?,
    onPress: () -> Unit,
    onBackspace: () -> Unit,
    onOk: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            flight?.let { PaySummary(it.pay, emphasize = false) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Enter UPI PIN", style = MaterialTheme.typography.titleLarge)
            }
            PinDots(pinLength)
            Text(
                "Any 4 to 6 digits. The PIN is not stored.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            error?.let { ErrorLine(it, center = true) }
        }
        MaskedPinPad(onDigit = onPress, onBackspace = onBackspace, onOk = onOk)
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Cancel") }
    }
}

@Composable
private fun PinDots(pinLength: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = "Masked PIN, $pinLength digits entered" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(6) { index ->
            if (index > 0) Spacer(Modifier.width(16.dp))
            val filled = index < pinLength
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (filled) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .border(
                        2.dp,
                        if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun MaskedPinPad(onDigit: () -> Unit, onBackspace: () -> Unit, onOk: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("Del", "0", "OK"),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { key ->
                        val primary = key == "OK"
                        PinKey(
                            label = key,
                            primary = primary,
                            onClick = when (key) {
                                "Del" -> onBackspace
                                "OK" -> onOk
                                else -> onDigit
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(0.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
        border = if (primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(label, fontSize = if (label.length > 1) 16.sp else 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SuccessBody(pay: ResolvedPay?, atMs: Long, onNew: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SuccessMark()
        Text("Done", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            KavachPay.SUCCESS_TEXT,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (pay != null) PaySummary(pay, emphasize = true)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Transaction ID",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    KavachPay.transactionId(atMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
            Text("New payment")
        }
    }
}

@Composable
private fun SuccessMark() {
    val scale = remember { Animatable(0.5f) }
    val alpha = remember { Animatable(0f) }
    val ring = remember { Animatable(0.7f) }
    val ringAlpha = remember { Animatable(0f) }
    val tint = MaterialTheme.colorScheme.onSecondaryContainer
    LaunchedEffect(Unit) {
        launch {
            ringAlpha.snapTo(0.7f)
            launch { ring.animateTo(1.45f, tween(700)) }
            ringAlpha.animateTo(0f, tween(700))
        }
        launch { alpha.animateTo(1f, tween(220)) }
        scale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        )
    }
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(88.dp)
                .scale(ring.value)
                .alpha(ringAlpha.value)
                .border(3.dp, tint, CircleShape),
        )
        Box(
            Modifier
                .size(88.dp)
                .scale(scale.value)
                .alpha(alpha.value)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun HistoryBody(history: List<SimulatedPayment>, onBack: () -> Unit) {
    SubScreen(title = "History", onBack = onBack) {
        Text(
            "Simulated payments from this session. No bank is involved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (history.isEmpty()) {
            Text("No simulated payments in this session yet.", style = MaterialTheme.typography.bodyLarge)
        } else {
            PayCard {
                history.asReversed().forEachIndexed { index, item ->
                    HistoryRow(item)
                    if (index != history.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: SimulatedPayment) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialCircle(initialsOf(item.label), colorForLabel(item.label), 48.dp, selected = false)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.label, style = MaterialTheme.typography.titleMedium)
            Text(item.upiId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${clockLabel(item.atMs)} · ${KavachPay.transactionId(item.atMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.note.isNotBlank()) {
                Text(item.note, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(KavachPay.formatRupees(item.amountRupees), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BalanceBody(onBack: () -> Unit) {
    SubScreen(title = "Bank balance", onBack = onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(PayHeaderBrush, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Kavach Demo Bank", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Demo Savings", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text("Available balance", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text(
                KavachPay.formatRupees(FAKE_BALANCE_RUPEES),
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text("···· 4291", color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
        Text("This balance is fake. No bank is connected.", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QrBody(onBack: () -> Unit) {
    SubScreen(title = "Scan QR", onBack = onBack) {
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                QrFrame(Modifier.fillMaxSize())
                Icon(
                    Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        Text(
            "QR scanning is simulated",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            "Nothing is captured from the camera.",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QrFrame(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val arm = size.minDimension * 0.22f
        val inset = stroke / 2f
        val right = size.width - inset
        val bottom = size.height - inset
        fun horizontal(x1: Float, x2: Float, y: Float) {
            drawLine(color, Offset(x1, y), Offset(x2, y), stroke, cap = StrokeCap.Round)
        }
        fun vertical(x: Float, y1: Float, y2: Float) {
            drawLine(color, Offset(x, y1), Offset(x, y2), stroke, cap = StrokeCap.Round)
        }
        horizontal(inset, inset + arm, inset)
        vertical(inset, inset, inset + arm)
        horizontal(right - arm, right, inset)
        vertical(right, inset, inset + arm)
        horizontal(inset, inset + arm, bottom)
        vertical(inset, bottom - arm, bottom)
        horizontal(right - arm, right, bottom)
        vertical(right, bottom - arm, bottom)
    }
}

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PayBackButton(onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}

@Composable
private fun PayBackButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 56.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Back", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun PayeeRow(selection: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        KavachPay.savedPayees.forEachIndexed { index, payee ->
            PayeeAvatar(
                label = payee.label,
                initials = initialsOf(payee.label),
                color = PayeePalette[index % PayeePalette.size],
                selected = selection == payee.id,
                add = false,
                onClick = { onSelect(payee.id) },
            )
        }
        PayeeAvatar(
            label = "New payee",
            initials = "+",
            color = NewPayeeColor,
            selected = selection == KavachPay.NEW_PAYEE,
            add = true,
            onClick = { onSelect(KavachPay.NEW_PAYEE) },
        )
    }
}

@Composable
private fun RowScope.PayeeAvatar(
    label: String,
    initials: String,
    color: Color,
    selected: Boolean,
    add: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (add) {
            Box(
                Modifier
                    .size(56.dp)
                    .border(
                        3.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        } else {
            InitialCircle(initials, color, 56.dp, selected)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
        )
    }
}

@Composable
private fun InitialCircle(initials: String, color: Color, size: androidx.compose.ui.unit.Dp, selected: Boolean) {
    val font = if (size <= 48.dp) 18.sp else 20.sp
    val ring = if (selected) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp)
    } else {
        Modifier
    }
    Box(
        Modifier
            .size(size)
            .then(ring)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = font,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun PaySummary(pay: ResolvedPay, emphasize: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(pay.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            pay.upiId,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            KavachPay.formatRupees(pay.amountRupees),
            fontSize = if (emphasize) 40.sp else 28.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        if (pay.note.isNotBlank()) {
            Text(
                pay.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PayCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun StatusNote(text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun ErrorLine(text: String, center: Boolean = false) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun sanitizeAmount(raw: String): String {
    val out = StringBuilder()
    var dots = 0
    for (ch in raw) {
        when {
            ch.isDigit() -> out.append(ch)
            ch == ',' -> out.append(ch)
            ch == '.' && dots == 0 -> {
                dots++
                out.append(ch)
            }
        }
        if (out.length >= 14) break
    }
    return out.toString()
}

private fun initialsOf(label: String): String = label.trim().take(1).uppercase(Locale.US)

private fun colorForLabel(label: String): Color {
    val index = KavachPay.savedPayees.indexOfFirst { it.label == label }
    return if (index >= 0) PayeePalette[index % PayeePalette.size] else NewPayeeColor
}

private fun clockLabel(atMs: Long): String {
    val z = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())
    return "%02d:%02d".format(z.hour, z.minute)
}

/**
 * Waits until the live engine has scored the AMOUNT_ENTERED event.
 * Returns null if that decision does not show up within two seconds.
 */
private suspend fun awaitAmountDecision(live: KavachEngine, amountAt: Long, before: Decision?): Decision? {
    val deadline = System.currentTimeMillis() + 2_000
    while (System.currentTimeMillis() < deadline) {
        val latest = live.latest.value
        val logged = live.log.value.any { entry ->
            entry.kind == "event" && entry.atMs == amountAt && "AMOUNT_ENTERED" in entry.text
        }
        if (logged && latest != null && latest !== before && latest.atMs >= amountAt) return latest
        delay(16)
    }
    return null
}
