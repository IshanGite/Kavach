package com.kavach.core

import com.kavach.core.TestKit.at
import com.kavach.core.TestKit.pay
import com.kavach.core.baseline.InMemoryBaseline
import com.kavach.core.baseline.PastPayment
import com.kavach.core.llm.*
import com.kavach.core.model.*
import com.kavach.core.risk.RiskEngine
import com.kavach.core.risk.RuleCode
import com.kavach.core.state.reduce
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class PerPayeeBaselineTest {
    private fun base() = InMemoryBaseline(
        (0 until 6).map { PastPayment("landlord@okhdfcbank", 18000.0, it.toLong()) } + (0 until 8).map { PastPayment("milk@upi", 60.0, it.toLong()) },
    )

    @Test
    fun `paying a known payee their usual amount is not unusual even if it dwarfs the global median`() {
        val r = RiskEngine(TestKit.cfg, base()).evaluate(TestKit.session(pay(1, PayStage.AMOUNT_ENTERED, 18000.0, "landlord@okhdfcbank")))
        assertTrue(r.rules.none { it.code == RuleCode.AMOUNT_ABOVE_USUAL }, "${r.rules}")
    }

    @Test
    fun `the same amount to a stranger is unusual`() {
        val r = RiskEngine(TestKit.cfg, base()).evaluate(TestKit.session(pay(1, PayStage.AMOUNT_ENTERED, 18000.0, "stranger@ybl")))
        assertTrue(r.rules.any { it.code == RuleCode.AMOUNT_ABOVE_USUAL })
    }

    @Test
    fun `a known payee paid far more than they usually get still flags`() {
        val r = RiskEngine(TestKit.cfg, base()).evaluate(TestKit.session(pay(1, PayStage.AMOUNT_ENTERED, 90000.0, "landlord@okhdfcbank")))
        assertTrue(r.rules.any { it.code == RuleCode.AMOUNT_ABOVE_USUAL })
    }

    @Test
    fun `per-payee median`() {
        assertEquals(18000.0, base().medianAmountFor("Landlord@okhdfcbank")); assertNull(base().medianAmountFor("nobody@ybl")); assertNull(base().medianAmountFor(null))
    }
}

class RepeatedNewPayeesTest {
    private val engine = TestKit.engine()

    private fun attempt(sec: Int, payee: String, amount: Double) = listOf(pay(sec, PayStage.OPENED), pay(sec + 5, PayStage.AMOUNT_ENTERED, amount, payee), PaymentDismissed(at(sec + 20)))

    @Test
    fun `second and third new payee in a day fire the rule, escalation adds more`() {
        val first = TestKit.session(*attempt(0, "task.one@ybl", 1000.0).toTypedArray(), pay(100, PayStage.AMOUNT_ENTERED, 3000.0, "task.two@ybl"))
        val r = engine.evaluate(first)
        assertTrue(r.rules.any { it.code == RuleCode.REPEATED_NEW_PAYEES }); assertTrue(r.rules.any { it.code == RuleCode.ESCALATING_NEW_PAYEE_AMOUNTS })
    }

    @Test
    fun `the very first new payee never fires it`() {
        val r = engine.evaluate(TestKit.session(pay(1, PayStage.AMOUNT_ENTERED, 5000.0, "task.one@ybl")))
        assertTrue(r.rules.none { it.code == RuleCode.REPEATED_NEW_PAYEES })
    }

    @Test
    fun `known payees in the history do not count`() {
        val r = engine.evaluate(TestKit.session(*attempt(0, "ramesh.kirana@ybl", 200.0).toTypedArray(), pay(100, PayStage.AMOUNT_ENTERED, 5000.0, "task.two@ybl")))
        assertTrue(r.rules.none { it.code == RuleCode.REPEATED_NEW_PAYEES })
    }

    @Test
    fun `attempts older than the window expire`() {
        val old = attempt(0, "task.one@ybl", 1000.0).toTypedArray()
        val r = engine.evaluate(TestKit.session(*old, pay(26 * 3600, PayStage.AMOUNT_ENTERED, 3000.0, "task.two@ybl")))
        assertTrue(r.rules.none { it.code == RuleCode.REPEATED_NEW_PAYEES })
    }

    @Test
    fun `a payment screen that only opened, without a payee, is not an attempt`() {
        val r = engine.evaluate(TestKit.session(pay(0, PayStage.OPENED), PaymentDismissed(at(5)), pay(100, PayStage.AMOUNT_ENTERED, 3000.0, "task.two@ybl")))
        assertTrue(r.rules.none { it.code == RuleCode.REPEATED_NEW_PAYEES })
    }

    @Test
    fun `shrinking amounts are repeated but not escalating`() {
        val r = engine.evaluate(TestKit.session(*attempt(0, "task.one@ybl", 9000.0).toTypedArray(), pay(100, PayStage.AMOUNT_ENTERED, 4000.0, "task.two@ybl")))
        assertTrue(r.rules.any { it.code == RuleCode.REPEATED_NEW_PAYEES }); assertTrue(r.rules.none { it.code == RuleCode.ESCALATING_NEW_PAYEE_AMOUNTS })
    }

    @Test
    fun `attempt history survives a new call session`() {
        val o = TestKit.orchestrator()
        attempt(0, "task.one@ybl", 1000.0).forEach { o.onEvent(it) }
        o.onEvent(TestKit.unknownCall(100)); o.onEvent(CallEnded(at(130), 30)); o.onEvent(TestKit.unknownCall(200))
        assertEquals(listOf("task.one@ybl"), o.session.payeeAttempts.map { it.payee })
    }
}

class PromptAndTransportTest {
    @Test
    fun `compact prompt keeps the injection fence, the schema and is much smaller`() {
        val full = Prompts.TRANSCRIPT_SYSTEM; val compact = Prompts.TRANSCRIPT_SYSTEM_COMPACT
        assertTrue(compact.contains("untrusted")); assertTrue(compact.contains("<transcript>"))
        listOf("\"id\"", "\"t\"", "\"type\"", "\"risk\"", "\"q\"", "\"p\"", "never names").forEach { assertTrue(compact.contains(it), "compact prompt lost $it") }
        assertTrue(compact.count { it == '<' } >= 4, "needs a scam example AND a normal example")
        assertTrue(compact.length < full.length / 2, "compact ${compact.length} vs full ${full.length}")
    }

    @Test
    fun `ollama keeps the model loaded between calls`() = runBlocking {
        var body = ""
        val t = object : HttpTransport { override suspend fun postJson(url: String, headers: Map<String, String>, body0: String, timeoutMs: Int): HttpResult { body = body0; return HttpResult(200, """{"message":{"content":"{}"}}""") } }
        OllamaClient(transport = t).analyze(WARMUP_REQUEST)
        assertEquals("30m", Json.parseToJsonElement(body).jsonObject["keep_alive"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the compact prompt is parseable by the same parser, via a scripted model`() = runBlocking {
        val analyst = com.kavach.core.analysis.HybridTranscriptAnalyst(
            com.kavach.core.sim.ScriptedLlmClient { req -> assertEquals(Prompts.TRANSCRIPT_SYSTEM_COMPACT, req.system); """{"tactics":{"authority_impersonation":1},"llm_risk":77}""" },
            TestKit.cfg, compactPrompt = { true },
        )
        val f = analyst.analyze(com.kavach.core.orchestrator.AnalysisRequest(0, "hello", 1, false, CallChannel.CELLULAR))
        assertTrue(f.source.startsWith("scripted"), "the scripted model's answer was parsed: ${f.source}")
    }
}

class LexiconGeneralisationTest {
    private fun a(t: String) = com.kavach.core.analysis.KeywordAnalyst.analyze(t)

    @Test
    fun `advice about what others might ask is not itself a request`() {
        val warn = a("If anyone asks you to install any app or share a code, please disconnect. Beware of fraudsters who ask for your OTP.")
        assertEquals(0.0, warn.tactics.remoteAccessOrOtpRequest)
    }

    @Test
    fun `but a scammer cannot hide a real app-install request behind a decoy warning sentence`() {
        val scam = a("If anyone asks, tell them it is a bank survey. Now install the AnyDesk application and tell me the code on your screen.")
        assertTrue(scam.tactics.remoteAccessOrOtpRequest >= 0.6, "${scam.tactics}")
    }

    @Test
    fun `different phrasings of secrecy are all recognised`() {
        listOf(
            "You must not discuss this with anybody", "Please do not share these details with your family", "do not tell mom", "kisi se baat mat kijiye",
            "It should not be disclosed to relatives", "remain on this call until verification is done",
        ).forEach { assertTrue(a(it).tactics.secrecyOrIsolation >= 0.6, it) }
    }

    @Test
    fun `different phrasings of a money request are recognised`() {
        listOf(
            "move your balance to the secure holding account", "transfer the amount to the supervised account", "pay the processing fee to release the prize",
            "send it to this number quickly", "aap paise bhej dijiye is khate mein", "I need forty thousand rupees for the lawyer",
        ).forEach { assertTrue(a(it).tactics.paymentOrTransferRequest >= 0.6, it) }
    }

    @Test
    fun `everyday money talk between people stays below the threshold`() {
        listOf(
            "I will pay you back tonight, thanks for the dinner", "the plumber charges 500 rupees for the visit", "can you send me the photos from the trip",
            "rent ka reminder tha, kiraya same account mein bhej dena", "bhai 5000 bhej de, kal wapas kar dunga",
        ).forEach { assertTrue(a(it).llmRisk < 35, "$it -> ${a(it).llmRisk}") }
    }

    @Test
    fun `blackmail with no official claim is still coercion`() {
        assertTrue(a("We have your private video. If you do not pay twenty thousand rupees right now we will send it to all your contacts and your family. Don't tell anyone.").llmRisk >= 60)
    }
}

class BankSignalTest {
    @Test
    fun `signal carries risk, tier and signal categories only, never numbers, names, transcript or amounts`() {
        val o = TestKit.orchestrator()
        o.onEvent(TestKit.unknownCall(number = "+91 99887 76655")); o.onEvent(TestKit.say(3, "you will be arrested do not tell anyone transfer money to verify dot pay at oksbi"))
        val d = o.onEvent(pay(20, PayStage.AMOUNT_ENTERED, 50000.0, "verify.pay@oksbi"))
        val json = com.kavach.core.risk.BankSignal.toJson(d.result, at(21))
        assertContains(json, "\"tier\""); assertContains(json, "payment_in_progress"); assertContains(json, "payment_app_during_call")
        assertFalse(json.contains("99887")); assertFalse(json.contains("verify")); assertFalse(json.contains("50000")); assertFalse(json.contains("arrested"))
        assertContains(json, "hold_and_call_back_customer")
    }

    @Test
    fun `quiet normal payment recommends nothing`() {
        val d = TestKit.orchestrator().onEvent(pay(1, PayStage.AMOUNT_ENTERED, 200.0, "ramesh.kirana@ybl"))
        assertContains(com.kavach.core.risk.BankSignal.toJson(d.result, at(2)), "\"recommended_bank_action\": \"none\"")
    }
}


class TerseSchemaAndPayeeNameTest {
    private val terse = """{"id":"police","t":[0.95,0.9,0.5,0.8,0.95,0,0],"type":"digital_arrest","risk":96,"q":["You will be arrested"],"p":["verify@oksbi"]}"""

    @Test
    fun `terse schema parses to the same findings as the verbose one`() {
        val f = assertNotNull(com.kavach.core.analysis.FindingsParser.parseFindings(terse, "x"))
        assertEquals(ClaimedIdentity.POLICE, f.claimedIdentity); assertEquals(ScamType.DIGITAL_ARREST, f.scamType); assertEquals(96, f.llmRisk)
        assertEquals(0.95, f.tactics.authorityImpersonation); assertEquals(0.9, f.tactics.threatOfArrestOrLegalAction); assertEquals(0.8, f.tactics.secrecyOrIsolation)
        assertEquals(listOf("verify@oksbi"), f.payeeDetailsMentioned); assertEquals(listOf("You will be arrested"), f.evidenceQuotes)
    }

    @Test
    fun `terse schema is robust to short arrays, strings and out of range numbers`() {
        assertNull(com.kavach.core.analysis.FindingsParser.parseFindings("""{"t":[1,2],"q":[]}""", "x"), "short array and no risk is not an analysis")
        val f = assertNotNull(com.kavach.core.analysis.FindingsParser.parseFindings("""{"id":"Mossad","t":["2","-1",0,0,0,0,0],"risk":"300"}""", "x"))
        assertEquals(1.0, f.tactics.authorityImpersonation); assertEquals(0.0, f.tactics.threatOfArrestOrLegalAction); assertEquals(100, f.llmRisk); assertEquals(ClaimedIdentity.NONE, f.claimedIdentity)
    }

    @Test
    fun `only upi ids and account numbers are payee identifiers, names are not`() {
        assertTrue(com.kavach.core.text.Extractors.isPayeeIdentifier("verify.pay@oksbi")); assertTrue(com.kavach.core.text.Extractors.isPayeeIdentifier("5010 0234 5678"))
        assertFalse(com.kavach.core.text.Extractors.isPayeeIdentifier("Sunrise Clinic")); assertFalse(com.kavach.core.text.Extractors.isPayeeIdentifier("Dr Mehta")); assertFalse(com.kavach.core.text.Extractors.isPayeeIdentifier("12345"))
    }

    @Test
    fun `a business name spoken on the call and shown on the pay screen is not a dictated payee`() {
        assertFalse(com.kavach.core.text.Extractors.payeeMatchesSpoken("Sunrise Clinic", listOf("sunrise clinic")))
        assertTrue(com.kavach.core.text.Extractors.payeeMatchesSpoken("verify.pay@oksbi", listOf("verify.pay@oksbi")))
    }

    @Test
    fun `an llm-supplied name never enters the spoken payees even when it appears in the transcript`() {
        val s = TestKit.session(TestKit.unknownCall(), TestKit.say(1, "Hello this is Sunrise Clinic confirming your appointment tomorrow"))
            .reduce(FindingsReady(at(5), TacticsFindings(payeeDetailsMentioned = listOf("Sunrise Clinic", "sunrise.clinic@ybl"))), TestKit.cfg)
        assertTrue("sunrise clinic" !in s.spokenPayees)
    }
}
