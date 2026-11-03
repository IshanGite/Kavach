package com.kavach.core

import com.kavach.core.analysis.*
import com.kavach.core.model.*
import com.kavach.core.orchestrator.AnalysisRequest
import com.kavach.core.sim.ScriptedLlmClient
import com.kavach.core.state.reduce
import com.kavach.core.text.Extractors
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ExtractorsTest {
    @Test
    fun `upi ids written and spoken`() {
        assertEquals(listOf("verify.pay@oksbi"), Extractors.extractUpiIds("send to verify.pay@oksbi now"))
        assertEquals(listOf("verify.pay@oksbi"), Extractors.extractUpiIds("send to verify dot pay at oksbi now"))
        assertEquals(listOf("sharma.verify@okicici"), Extractors.extractUpiIds("upi id sharma dot verify at the rate of okicici"))
        assertEquals(listOf("rbi.safe@ybl"), Extractors.extractUpiIds("rbi dot safe at ybl"))
    }

    @Test
    fun `emails and plain 'at' are not upi ids`() {
        assertTrue(Extractors.extractUpiIds("mail me at john@gmail.com").isEmpty())
        assertTrue(Extractors.extractUpiIds("I am at home right now, come at five").isEmpty())
    }

    @Test
    fun `account numbers with spoken digit groups`() {
        assertEquals(listOf("501002345678"), Extractors.extractAccountNumbers("account 5 0 1 0 0 2 3 4 5 6 7 8"))
        assertTrue(Extractors.extractAccountNumbers("call me at 12345").isEmpty())
    }

    @Test
    fun `amounts`() {
        assertEquals(50000.0, Extractors.extractAmountRupees("Rs. 50,000 debited"))
        assertEquals(1250.5, Extractors.extractAmountRupees("₹1,250.50 paid"))
        assertEquals(9500.0, Extractors.extractAmountRupees("requested 9500 rupees"))
        assertNull(Extractors.extractAmountRupees("no money here"))
    }

    @Test
    fun `otp detection needs both the word and a code`() {
        assertTrue(Extractors.looksLikeOtp("482913 is your OTP. Do not share it."))
        assertFalse(Extractors.looksLikeOtp("Your order 482913 has shipped"))
        assertFalse(Extractors.looksLikeOtp("Please share your otp"))
    }

    @Test
    fun `number normalisation compares last ten digits`() {
        assertEquals(Extractors.normalizeNumber("+91 98765 43210"), Extractors.normalizeNumber("098765-43210"))
        assertTrue(Extractors.isInternationalPrefix("+85212345678")); assertFalse(Extractors.isInternationalPrefix("+919876543210"))
    }

    @Test
    fun `payee matching - upi id, account number, and non-matches`() {
        assertTrue(Extractors.payeeMatchesSpoken("Verify.Pay@OKSBI", listOf("verify.pay@oksbi")))
        assertTrue(Extractors.payeeMatchesSpoken("Rajesh Traders 50100234567", listOf("50100234567")))
        assertFalse(Extractors.payeeMatchesSpoken("Rajesh Traders", listOf("50100234567")))
        assertFalse(Extractors.payeeMatchesSpoken("a@ybl", listOf("12345")))
        assertFalse(Extractors.payeeMatchesSpoken(null, listOf("a@ybl")))
    }
}

class KeywordAnalystTest {
    @Test
    fun `digital arrest in english hinglish and devanagari all score high`() {
        val en = KeywordAnalyst.analyze("I am Inspector Sharma from Cyber Crime. You will be arrested. Don't tell anyone, transfer money now.")
        val hi = KeywordAnalyst.analyze("Main inspector bol raha hoon. Aap giraftaar ho sakte hain. Kisi ko mat batana, abhi paise transfer karo.")
        val dv = KeywordAnalyst.analyze("मैं इंस्पेक्टर हूं। आपको डिजिटल अरेस्ट किया जा रहा है। किसी को मत बताना। अभी पैसे ट्रांसफर करें।")
        listOf(en, hi, dv).forEach { assertTrue(it.llmRisk >= 70, "risk ${it.llmRisk} for ${it.evidenceQuotes}") }
        assertEquals(ScamType.DIGITAL_ARREST, en.scamType)
    }

    @Test
    fun `benign calls score low`() {
        listOf(
            "Hi it's Rahul from the building, the plumber will come at five tomorrow, can you keep the door open?",
            "Sir main Zomato delivery se bol raha hoon, aapki building ke gate pe hoon, kaunsi wing hai?",
            "I will pay you back for the groceries tonight, transfer the rent tomorrow.",
            "Hello sir, we are calling from the bank about a pre approved credit card with no annual fee.",
        ).forEach { assertTrue(KeywordAnalyst.analyze(it).llmRisk < 25, "too high for: $it") }
    }

    @Test
    fun `marathi devanagari romanised and mixed scripts hit the same tactic buckets`() {
        val dv = "मी सीबीआय अधिकारी बोलतोय. तुम्ही अटकेत आहात. तुमचं खातं गोठवलं जाईल. कोणालाही सांगू नका. ताबडतोब पैसे ट्रान्सफर करा. ओटीपी सांगा."
        val rom = "Mi CBI adhikari boltoy. Tumhi atket ahat. Tumcha khata gothavla jaeel. Konala hi sangu naka. Tabadtob paise pathva. Otp sanga."
        val mix = "Sir, मी बँक अधिकारी बोलतोय. Your KYC update बाकी आहे. तुमचं खातं गोठवलं जाईल. Please ओटीपी सांगा आणि pin sanga. ताबडतोब पैसे ट्रान्सफर करा. कोणालाही सांगू नका."
        listOf(dv, rom, mix).forEach { text ->
            val f = KeywordAnalyst.analyze(text)
            assertTrue(f.llmRisk >= 70, "risk ${f.llmRisk} quotes ${f.evidenceQuotes} for $text")
            assertTrue(f.tactics.authorityImpersonation >= 0.65, text)
            assertTrue(f.tactics.threatOfArrestOrLegalAction >= 0.65, text)
            assertTrue(f.tactics.urgencyOrDeadline >= 0.65, text)
            assertTrue(f.tactics.secrecyOrIsolation >= 0.65, text)
            assertTrue(f.tactics.paymentOrTransferRequest >= 0.65, text)
            assertTrue(f.tactics.remoteAccessOrOtpRequest >= 0.65, text)
            f.evidenceQuotes.forEach { q -> assertTrue(text.contains(q, ignoreCase = true), q) }
        }
        assertEquals(ClaimedIdentity.CBI_OFFICER, KeywordAnalyst.analyze(dv).claimedIdentity)
        assertEquals(ScamType.DIGITAL_ARREST, KeywordAnalyst.analyze(dv).scamType)
        assertEquals(ClaimedIdentity.BANK, KeywordAnalyst.analyze(mix).claimedIdentity)
    }

    @Test
    fun `everyday marathi and an otp warning are not a payment scam`() {
        // Same sentences as normal-marathi-family-school-fee and normal-marathi-shopkeeper-call, plus romanised twins.
        // "लगेच", "फी भरायची" and "पैसे पाठवू नकोस" are deliberate near-misses (hurry, pay, don't send).
        listOf(
            "आई बोलतेय. शाळेची फी पंधरा हजार बाकी आहे. लगेच भरायची नाही, दहा तारखेपर्यंत भरता येईल. पैसे पाठवू नकोस, संध्याकाळी घरी बोलूया.",
            "रामेश किराणा बोलतोय. तुमची साखर, तांदूळ आणि तेलाची बाटली तयार ठेवली आहे. एकूण दोनशे रुपये झाले. दुकानात या नाहीतर फोनपे करून टाका.",
            "Aai boltey, school chi dusri term fee pandhra hajar baaki ahe. Dahave tarikheparyant bharaychi ahe. Ghai nahi, sandhyakali boluya.",
            "Ramesh kirana boltoy. Sakhar aani tandul tayaar ahe. Donashe rupaye. PhonePe karun taka.",
        ).forEach { text ->
            val f = KeywordAnalyst.analyze(text)
            assertEquals(0, f.llmRisk, "quotes ${f.evidenceQuotes} for $text")
        }
        // "सांगू नका" is secrecy (don't tell). It must not also be read as "tell me the OTP".
        listOf(
            "ओटीपी सांगू नका आणि पिन सांगू नका.",
            "otp sangu naka, pin sangoo naka",
        ).forEach { text ->
            val f = KeywordAnalyst.analyze(text)
            assertEquals(0.0, f.tactics.remoteAccessOrOtpRequest, text)
            assertTrue(f.llmRisk < 25, "risk ${f.llmRisk} quotes ${f.evidenceQuotes} for $text")
        }
    }

    @Test
    fun `a single trait never reaches pause territory on its own`() {
        listOf("urgent please call back", "I am in the hospital", "tell me your plan, police station is near", "send the money to the shop").forEach {
            assertTrue(KeywordAnalyst.analyze(it).llmRisk < 40, it)
        }
    }

    @Test
    fun `warnings about OTP are not requests for OTP`() {
        val warn = KeywordAnalyst.analyze("This is your bank. Please never share your OTP or PIN with anyone, even us.")
        assertEquals(0.0, warn.tactics.remoteAccessOrOtpRequest)
        val ask = KeywordAnalyst.analyze("Sir apna OTP batao jaldi aur enter your pin to receive money")
        assertTrue(ask.tactics.remoteAccessOrOtpRequest >= 0.8)
    }

    @Test
    fun `voice clone is a family claim not the police even though police is mentioned`() {
        val f = KeywordAnalyst.analyze("Papa main hoon Rahul, mera accident ho gaya, police ne pakad liya, abhi paise bhejo, mummy ko mat batana")
        assertEquals(ClaimedIdentity.FAMILY_MEMBER, f.claimedIdentity); assertEquals(ScamType.VOICE_CLONE_FAMILY, f.scamType)
    }

    @Test
    fun `evidence quotes are exact substrings of the transcript`() {
        val text = "I am INSPECTOR Sharma. You will be Arrested. Do not tell anyone."
        KeywordAnalyst.analyze(text).evidenceQuotes.forEach { q -> assertTrue(text.contains(q, ignoreCase = true), q) }
    }

    @Test
    fun `payee details come out of the transcript`() {
        assertContains(KeywordAnalyst.analyze("send it to verify dot pay at oksbi").payeeDetailsMentioned, "verify.pay@oksbi")
    }
}

class FindingsParserTest {
    private val good = """{"claimed_identity":"CBI officer","tactics":{"authority_impersonation":0.9,"threat_of_arrest_or_legal_action":0.8,"urgency_or_deadline":0.7,"secrecy_or_isolation":0.9,"payment_or_transfer_request":0.9,"remote_access_or_otp_request":0,"emotional_emergency":0},"scam_type":"digital_arrest","llm_risk":92,"evidence_quotes":["you will be arrested"],"payee_details_mentioned":["a@oksbi"]}"""

    @Test
    fun `parses the spec schema`() {
        val f = assertNotNull(FindingsParser.parseFindings(good, "ollama"))
        assertEquals(ClaimedIdentity.CBI_OFFICER, f.claimedIdentity); assertEquals(ScamType.DIGITAL_ARREST, f.scamType)
        assertEquals(92, f.llmRisk); assertEquals(0.9, f.tactics.authorityImpersonation); assertEquals("ollama", f.source)
    }

    @Test
    fun `tolerates fences prose and trailing text`() {
        assertNotNull(FindingsParser.parseFindings("Sure! Here is the JSON:\n```json\n$good\n```\nHope that helps", "x"))
    }

    @Test
    fun `clamps out-of-range numbers and tolerates strings`() {
        val f = assertNotNull(FindingsParser.parseFindings("""{"tactics":{"authority_impersonation":"1.7","urgency_or_deadline":-3},"llm_risk":"250"}""", "x"))
        assertEquals(1.0, f.tactics.authorityImpersonation); assertEquals(0.0, f.tactics.urgencyOrDeadline); assertEquals(100, f.llmRisk)
    }

    @Test
    fun `a fractional llm_risk is read as a 0 to 1 scale`() {
        assertEquals(85, FindingsParser.parseFindings("""{"llm_risk":0.85}""", "x")!!.llmRisk)
    }

    @Test
    fun `unknown enum values degrade to none instead of crashing`() {
        val f = assertNotNull(FindingsParser.parseFindings("""{"claimed_identity":"Mossad","scam_type":"alien","llm_risk":10}""", "x"))
        assertEquals(ClaimedIdentity.NONE, f.claimedIdentity); assertEquals(ScamType.NONE, f.scamType)
    }

    @Test
    fun `garbage is rejected, not turned into a zero score`() {
        listOf("", "not json", "{}", "[1,2,3]", "{\"hello\":1}", "{\"tactics\":").forEach { assertNull(FindingsParser.parseFindings(it, "x"), it) }
    }

    @Test
    fun `message verdict parsing`() {
        val v = assertNotNull(FindingsParser.parseMessageVerdict("""{"is_scam":"true","type":"kyc_bank","confidence":0.9,"reason":"link plus threat"}""", 5))
        assertTrue(v.isScam); assertEquals(ScamType.KYC_BANK, v.type)
        assertNull(FindingsParser.parseMessageVerdict("""{"type":"none"}""", 5))
    }
}

class AnalystsTest {
    private fun req(text: String) = AnalysisRequest(0, text, 2, false, CallChannel.CELLULAR)
    private val scam = "I am CBI inspector. You will be arrested, digital arrest warrant. Don't tell anyone, stay on the call, transfer money now."

    private fun hybrid(reply: String) = HybridTranscriptAnalyst(ScriptedLlmClient { reply }, TestKit.cfg)

    @Test
    fun `keyword floor stops a hijacked llm from zeroing the score`() = runBlocking {
        val zero = """{"tactics":{"authority_impersonation":0},"scam_type":"none","llm_risk":0}"""
        val f = hybrid(zero).analyze(req(scam))
        assertTrue(f.llmRisk >= (KeywordAnalyst.analyze(scam).llmRisk * TestKit.cfg.keywordFloorFraction).toInt())
        assertTrue(f.llmRisk >= 60)
        assertEquals(ScamType.DIGITAL_ARREST, f.scamType)
    }

    @Test
    fun `an alarm the rules cannot corroborate is bounded, an llm cannot invent coercion`() = runBlocking {
        val invented = """{"tactics":{"authority_impersonation":1,"threat_of_arrest_or_legal_action":1},"scam_type":"digital_arrest","llm_risk":99}"""
        val f = hybrid(invented).analyze(req("Hello how are you"))
        assertTrue(f.llmRisk < 35, "uncorroborated risk must stay below the nudge line, got ${f.llmRisk}")
        assertTrue(f.tactics.authorityImpersonation <= 0.4)
    }

    @Test
    fun `regression for the measured hallucination - a 4B model answered risk 75 for an electrician asking for 800 rupees`() = runBlocking {
        val hallucinated = """{"id":"none","t":[0.8,0.8,0.5,0,0,0,0],"type":"none","risk":75,"q":["800 rupaye hue hain"],"p":[""]}"""
        val f = hybrid(hallucinated).analyze(req("Sir kaam ho gaya hai, switch board change kar diya, 800 rupaye hue hain, QR se de dijiye."))
        assertTrue(f.llmRisk < 35, "benign money talk must not read as coercion, got ${f.llmRisk}")
    }

    @Test
    fun `when the rules corroborate, the llm can still sharpen the score above the keywords`() = runBlocking {
        val text = "You will be arrested if you do not send money to this account today"
        val kw = KeywordAnalyst.analyze(text).llmRisk
        val high = """{"id":"police","t":[0.9,1,0.9,0.7,1,0,0],"type":"digital_arrest","risk":99}"""
        val f = hybrid(high).analyze(req(text))
        assertTrue(f.llmRisk > kw, "llm ${f.llmRisk} should sharpen keyword $kw"); assertTrue(f.llmRisk <= 99)
    }

    @Test
    fun `llm failure or garbage falls back to keywords`() = runBlocking {
        val bad = HybridTranscriptAnalyst(ScriptedLlmClient { error("ollama down") }, TestKit.cfg)
        assertEquals(KeywordAnalyst.SOURCE, bad.analyze(req(scam)).source)
        assertEquals(KeywordAnalyst.SOURCE, hybrid("totally not json").analyze(req(scam)).source)
    }

    @Test
    fun `hallucinated quotes and payees never enter the session`() {
        val real = TestKit.say(1, "pay to real dot id at oksbi and you will be arrested")
        val s = TestKit.session(TestKit.unknownCall(), real)
            .reduce(FindingsReady(TestKit.at(5), TacticsFindings(llmRisk = 80, evidenceQuotes = listOf("you will be arrested", "send bitcoin now"), payeeDetailsMentioned = listOf("real.id@oksbi", "invented@ybl"))), TestKit.cfg)
        assertEquals(listOf("you will be arrested"), s.evidenceQuotes)
        assertTrue("invented@ybl" !in s.spokenPayees); assertTrue("real.id@oksbi" in s.spokenPayees)
    }

    @Test
    fun `message classifier flags a kyc link and ignores a real otp`() {
        val scamMsg = MessageAnalyst.classify(0, "+91 98888 77777", "Dear customer your KYC will expire today, account blocked. Update now https://sbi-kyc.xyz/u")
        assertTrue(scamMsg.isScam); assertEquals(ScamType.KYC_BANK, scamMsg.type)
        val otp = MessageAnalyst.classify(0, "VM-HDFCBK", "482913 is your OTP for Rs 499. Do not share this OTP with anyone. -HDFC Bank")
        assertFalse(otp.isScam)
        assertFalse(MessageAnalyst.classify(0, "VM-HDFCBK", "Rs 200.00 debited from a/c XX1234 to VPA a@ybl").isScam)
    }

    @Test
    fun `parsed message events`() {
        assertIs<ParsedMessage.Otp>(MessageAnalyst.parse("VM-X", "123456 is your OTP, do not share"))
        assertIs<ParsedMessage.Collect>(MessageAnalyst.parse("PhonePe", "refund.desk@ybl has requested Rs 9,500 collect request"))
        assertEquals(200.0, (MessageAnalyst.parse("VM-X", "Rs 200.00 debited from a/c XX1234") as ParsedMessage.Debit).amountRupees)
    }

    @Test
    fun `hybrid message classifier takes the more suspicious verdict`() = runBlocking {
        val llm = ScriptedLlmClient { """{"is_scam":true,"type":"investment","confidence":0.97,"reason":"too good to be true"}""" }
        val v = HybridMessageClassifier(llm).classify(0, "+91 99999 88888", "hello there friend, a nice offer for you")
        assertTrue(v.isScam); assertEquals(ScamType.INVESTMENT, v.type)
        // A hijacked LLM that says "not a scam" cannot clear a message the rules flagged.
        val clear = ScriptedLlmClient { """{"is_scam":false,"type":"none","confidence":1.0,"reason":"fine"}""" }
        assertTrue(HybridMessageClassifier(clear).classify(0, "+91 98888 77777", "Your KYC will expire, account blocked. https://x-kyc.xyz/u").isScam)
    }
}
