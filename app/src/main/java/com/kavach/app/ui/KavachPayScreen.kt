package com.kavach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private enum class PayStep { FORM, WAITING, HELD, PIN, SUCCESS }

private data class InFlight(val pay: ResolvedPay, val amountAt: Long, val before: Decision?)

/**
 * Mock UPI flow wired to the live engine (`c.live`), the same one Debug and the sensors use.
 * Pay emits OPENED then AMOUNT_ENTERED. Tier 2+ shows the standard pause overlay before this PIN.
 */
@Composable
fun KavachPayScreen() {
    val ctx = LocalContext.current
    val live = ctx.container().live
    val overlay = ctx.container().overlay
    var step by remember { mutableStateOf(PayStep.FORM) }
    val stepNow = rememberUpdatedState(step)
    var form by remember { mutableStateOf(KavachPay.blankForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var flight by remember { mutableStateOf<InFlight?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var heldTier by remember { mutableIntStateOf(0) }
    var pinLength by remember { mutableIntStateOf(0) }
    var overlayMissing by remember { mutableStateOf(false) }
    val history by KavachPayLedger.items.collectAsState()

    DisposableEffect(live) {
        onDispose {
            when (stepNow.value) {
                PayStep.WAITING, PayStep.HELD, PayStep.PIN -> live.emit(PaymentDismissed(live.now()))
                PayStep.FORM, PayStep.SUCCESS -> Unit
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
            step = PayStep.FORM
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
        step = PayStep.FORM
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
        val at = live.now()
        KavachPayLedger.add(
            SimulatedPayment(current.pay.label, current.pay.upiId, current.pay.amountRupees, current.pay.note, at),
        )
        live.scope.launch {
            live.orchestrator.recordCompletedPayment(current.pay.upiId, current.pay.amountRupees, at)
        }
        live.emit(PaymentDismissed(at))
        step = PayStep.SUCCESS
    }

    Page {
        DemoBanner()
        when (step) {
            PayStep.FORM -> FormBody(
                form = form,
                error = error,
                status = status,
                history = history,
                onForm = { form = it },
                onRole = ::applyRole,
                onPay = ::startPay,
            )
            PayStep.WAITING -> {
                flight?.let { PaySummary(it.pay) }
                CircularProgressIndicator()
                Text("Checking this payment with the live engine.")
            }
            PayStep.HELD -> HeldBody(
                flight = flight,
                tier = heldTier,
                overlayMissing = overlayMissing,
                onContinue = { live.emit(UserProceeded(live.now())) },
                onCancel = {
                    live.emit(PaymentDismissed(live.now()))
                    flight = null
                    step = PayStep.FORM
                },
            )
            PayStep.PIN -> PinBody(
                flight = flight,
                pinLength = pinLength,
                error = error,
                onPress = { if (pinLength < 6) pinLength++ },
                onBackspace = { if (pinLength > 0) pinLength--; error = null },
                onOk = ::finishPayment,
                onCancel = {
                    pinLength = 0
                    live.emit(PaymentDismissed(live.now()))
                    flight = null
                    step = PayStep.FORM
                },
            )
            PayStep.SUCCESS -> {
                val pay = flight?.pay
                Text("Done", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (pay != null) PaySummary(pay)
                Text(KavachPay.SUCCESS_TEXT, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start)
                Button(onClick = { flight = null; step = PayStep.FORM }, modifier = Modifier.fillMaxWidth()) { Text("New payment") }
            }
        }
    }
}

@Composable
private fun FormBody(
    form: PayForm,
    error: String?,
    status: String?,
    history: List<SimulatedPayment>,
    onForm: (PayForm) -> Unit,
    onRole: (RolePlay) -> Unit,
    onPay: () -> Unit,
) {
    Text("Choose a saved contact or a new UPI id, then pay. Kavach sees the same events as a real UPI app.", fontSize = 14.sp)
    Text("Pay to", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (payee in KavachPay.savedPayees.take(2)) {
            FilterChip(
                selected = form.selection == payee.id,
                onClick = { onForm(form.copy(selection = payee.id)) },
                label = { Text(payee.label) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val landlord = KavachPay.savedPayees[2]
        FilterChip(
            selected = form.selection == landlord.id,
            onClick = { onForm(form.copy(selection = landlord.id)) },
            label = { Text(landlord.label) },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = form.selection == KavachPay.NEW_PAYEE,
            onClick = { onForm(form.copy(selection = KavachPay.NEW_PAYEE)) },
            label = { Text("New payee") },
            modifier = Modifier.weight(1f),
        )
    }
    val selected = KavachPay.savedPayees.firstOrNull { it.id == form.selection }
    if (selected != null) {
        Text(selected.upiId, fontSize = 13.sp)
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
    OutlinedTextField(
        value = form.amountText,
        onValueChange = { onForm(form.copy(amountText = it)) },
        label = { Text("Amount") },
        prefix = { Text("₹") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    OutlinedTextField(
        value = form.note,
        onValueChange = { onForm(form.copy(note = it)) },
        label = { Text("Note") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
    Button(onClick = onPay, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Pay") }

    Section("Role-play") {
        Text("Fills this form and injects events into the live engine, not the Simulate engine.", fontSize = 13.sp)
        Text("Coerced can text a saved guardian, because the live engine does that at tier 3.", fontSize = 13.sp)
        status?.let { Text(it, fontSize = 13.sp) }
        Button(onClick = { onRole(RolePlay.NORMAL) }, modifier = Modifier.fillMaxWidth()) { Text("Role-play: normal") }
        Button(onClick = { onRole(RolePlay.COERCED) }, modifier = Modifier.fillMaxWidth()) { Text("Role-play: coerced") }
    }

    Section("Simulated payments") {
        if (history.isEmpty()) {
            Text("None yet.", fontSize = 13.sp)
        } else {
            history.takeLast(8).asReversed().forEach { item ->
                val note = if (item.note.isBlank()) "" else " · ${item.note}"
                Text(
                    "${clockLabel(item.atMs)}  ${item.label}  ${KavachPay.formatRupees(item.amountRupees)}$note",
                    fontSize = 13.sp,
                )
                Text(item.upiId, fontSize = 12.sp)
            }
        }
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
    flight?.let { PaySummary(it.pay) }
    Text("Paused before the PIN", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Tier $tier. The pause overlay is the same one Kavach draws over a real UPI app. Continue there after the timer, and this PIN screen opens.", fontSize = 14.sp)
    if (overlayMissing) {
        Text("The overlay is not on screen. Enable the accessibility service or Display over other apps from Setup. You can still continue.", fontSize = 14.sp)
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue to PIN") }
    }
    TextButton(onClick = onCancel) { Text("Cancel") }
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
    flight?.let { PaySummary(it.pay) }
    Text("Enter UPI PIN", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Any 4 to 6 digits. The PIN is not stored.", fontSize = 13.sp)
    Text(
        if (pinLength == 0) "······" else "●".repeat(pinLength),
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
    rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { digit ->
                OutlinedButton(onClick = onPress, modifier = Modifier.weight(1f).height(56.dp)) { Text(digit, fontSize = 22.sp) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onBackspace, modifier = Modifier.weight(1f).height(56.dp)) { Text("Del", fontSize = 16.sp) }
        OutlinedButton(onClick = onPress, modifier = Modifier.weight(1f).height(56.dp)) { Text("0", fontSize = 22.sp) }
        Button(onClick = onOk, modifier = Modifier.weight(1f).height(56.dp)) { Text("OK", fontSize = 16.sp) }
    }
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun PaySummary(pay: ResolvedPay) {
    Column {
        Text("${pay.label} · ${pay.upiId}", fontWeight = FontWeight.Bold)
        Text(KavachPay.formatRupees(pay.amountRupees), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (pay.note.isNotBlank()) Text(pay.note, fontSize = 14.sp)
    }
}

@Composable
private fun DemoBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(KavachPay.DEMO_BANNER, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(KavachPay.PACKAGE_NAME, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
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
