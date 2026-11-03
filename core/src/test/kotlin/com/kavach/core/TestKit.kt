package com.kavach.core

import com.kavach.core.baseline.InMemoryBaseline
import com.kavach.core.baseline.PastPayment
import com.kavach.core.config.RiskConfig
import com.kavach.core.intervention.TrustedContact
import com.kavach.core.intervention.UserProfile
import com.kavach.core.model.*
import com.kavach.core.orchestrator.Orchestrator
import com.kavach.core.risk.RiskEngine
import com.kavach.core.state.SessionState
import com.kavach.core.state.reduce
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

object TestKit {
    val cfg: RiskConfig = RiskConfig.loadDefault()
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** 15:00 IST on 3 Nov 2026: a normal daytime hour. */
    val T0: Long = LocalDateTime.parse("2026-11-03T15:00:00").atZone(IST).toInstant().toEpochMilli()
    fun at(sec: Int, base: Long = T0) = base + sec * 1000L

    val guardian = TrustedContact("Anjali", "+919800000001", "daughter")
    val son = TrustedContact("Rahul", "+919800000002", "son")
    val profile = UserProfile(userName = "Papa", guardians = listOf(guardian), familyContacts = listOf(son, guardian), familySafeWordSet = true)

    fun baseline(extra: List<PastPayment> = emptyList()) = InMemoryBaseline(
        listOf(
            *(0 until 6).map { PastPayment("ramesh.kirana@ybl", 200.0, T0 - 86_400_000L * (it + 1)) }.toTypedArray(),
            *(0 until 4).map { PastPayment("rahul.son@okhdfcbank", 1500.0, T0 - 86_400_000L * (it + 1)) }.toTypedArray(),
            *(0 until 5).map { PastPayment("milk.dairy@upi", 60.0, T0 - 86_400_000L * (it + 1)) }.toTypedArray(),
        ) + extra,
    )

    fun orchestrator(scam: Set<String> = emptySet(), p: UserProfile = profile, speakNudges: Boolean = false) =
        Orchestrator(cfg, baseline(), p, { it.filter(Char::isDigit).takeLast(10) in scam }, speakNudges = speakNudges)

    fun engine() = RiskEngine(cfg, baseline())

    fun unknownCall(sec: Int = 0, number: String = "+91 99887 76655", channel: CallChannel = CallChannel.CELLULAR) =
        CallStarted(at(sec), number, channel = channel, isInContacts = false)

    fun savedCall(sec: Int = 0) = CallStarted(at(sec), "+91 98200 11122", isInContacts = true, contactName = "Rahul")

    fun pay(sec: Int, stage: PayStage = PayStage.OPENED, amount: Double? = null, payee: String? = null, app: String = "PhonePe") =
        PaymentIntent(at(sec), app, stage, amount, payee)

    fun say(sec: Int, text: String) = TranscriptChunk(at(sec), text, speaker = Speaker.CALLER)

    /** Reduce a list of events into a session (no orchestrator). */
    fun session(vararg events: KavachEvent, nowSec: Int? = null): SessionState {
        var s = SessionState()
        events.forEach { s = s.reduce(it, cfg) }
        return if (nowSec != null) s.copy(nowMs = at(nowSec)) else s
    }

    fun scenarioDir(): File = File("src/main/resources/scenarios").let { if (it.exists()) it else File("core/src/main/resources/scenarios") }

    fun reportDir(): File? = System.getProperty("kavach.reportDir")?.let { File(it).apply { mkdirs() } }
}
