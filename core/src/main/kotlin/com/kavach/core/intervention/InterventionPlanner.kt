package com.kavach.core.intervention

import com.kavach.core.config.RiskConfig
import com.kavach.core.model.ClaimedIdentity
import com.kavach.core.model.Tactics
import com.kavach.core.risk.HardOverride
import com.kavach.core.risk.RiskResult
import com.kavach.core.risk.RuleCode
import com.kavach.core.risk.Tier
import com.kavach.core.state.SessionState

enum class Lang(val tag: String) { EN("en-IN"), HI("hi-IN") }

data class TrustedContact(val name: String, val number: String, val relation: String = "")

data class UserProfile(
    val userName: String = "User",
    val language: Lang = Lang.EN,
    val guardians: List<TrustedContact> = emptyList(),
    /** Family members the user can call back; the first guardian is the default "big button". */
    val familyContacts: List<TrustedContact> = emptyList(),
    val familySafeWordSet: Boolean = false,
)

data class InterventionPlan(
    val tier: Tier,
    val lang: Lang,
    val headline: String,
    /** 2 or 3 plain reasons, specific to this call. Specific beats generic. */
    val reasons: List<String>,
    val mythBuster: String?,
    val callTrusted: TrustedContact?,
    val callTrustedLabel: String?,
    val askSafeWord: Boolean,
    /** Verify a claimed relative by calling their real saved number. */
    val callbackVerification: String?,
    val cooldownSeconds: Int,
    val questions: List<String>,
    val ifAnyYes: String,
    val spokenWarning: String?,
    /** National cybercrime helpline, 1930. Set on a tier 2/3 pause; null on a nudge. */
    val callHelpline: String? = null,
    val callHelplineLabel: String? = null,
)

/** Templated, deterministic planner: always available, never waits on an LLM, never hallucinates. */
class InterventionPlanner(private val cfg: RiskConfig) {

    fun plan(tier: Tier, result: RiskResult, s: SessionState, profile: UserProfile): InterventionPlan {
        val l = profile.language
        val reasons = buildReasons(result, s, l)
        val callee = pickCallee(s, profile)
        val familyClaim = s.claimedIdentity == ClaimedIdentity.FAMILY_MEMBER

        val headline = when (tier) {
            Tier.NONE -> ""
            Tier.NUDGE -> T.nudgeHeadline(l)
            Tier.PAUSE -> T.pauseHeadline(l)
            Tier.PAUSE_GUARDIAN -> T.pauseGuardianHeadline(l)
        }
        val cooldown = when (tier) {
            Tier.PAUSE -> cfg.pauseSecondsTier2
            Tier.PAUSE_GUARDIAN -> cfg.pauseSecondsTier3
            else -> 0
        }
        val spoken = when (tier) {
            Tier.NONE -> null
            Tier.NUDGE -> T.nudgeSpoken(l)
            else -> T.pauseSpoken(l)
        }
        val showHelpline = tier.level >= Tier.PAUSE.level
        return InterventionPlan(
            tier = tier,
            lang = l,
            headline = headline,
            reasons = reasons.take(if (tier == Tier.NUDGE) 2 else 3),
            mythBuster = mythBuster(s.claimedIdentity, l),
            callTrusted = callee,
            callTrustedLabel = callee?.let { T.callNow(l, it.name) },
            askSafeWord = familyClaim && profile.familySafeWordSet,
            callbackVerification = if (familyClaim) T.callbackVerify(l, callee?.name) else null,
            cooldownSeconds = cooldown,
            questions = if (showHelpline) T.questions(l) else emptyList(),
            ifAnyYes = T.ifAnyYes(l),
            spokenWarning = spoken,
            callHelpline = if (showHelpline) T.CYBER_HELPLINE else null,
            callHelplineLabel = if (showHelpline) T.callHelpline(l) else null,
        )
    }

    private fun pickCallee(s: SessionState, profile: UserProfile): TrustedContact? {
        // A claimed relative is verified by calling THAT relative's real number if we know it.
        if (s.claimedIdentity == ClaimedIdentity.FAMILY_MEMBER) {
            val hint = s.call?.contactName
            profile.familyContacts.firstOrNull { it.name.equals(hint, ignoreCase = true) }?.let { return it }
        }
        return profile.guardians.firstOrNull() ?: profile.familyContacts.firstOrNull()
    }

    internal fun buildReasons(r: RiskResult, s: SessionState, l: Lang): List<String> {
        val out = mutableListOf<String>()
        val t: Tactics? = s.findings.lastOrNull()?.tactics
        val id = s.claimedIdentity

        // Specific, call-derived reasons first: they are far more convincing than generic warnings.
        if (id != ClaimedIdentity.NONE && id != ClaimedIdentity.OTHER) {
            val parts = mutableListOf<String>()
            if (t != null && t.threatOfArrestOrLegalAction >= 0.6) parts += T.partThreat(l)
            if (t != null && t.secrecyOrIsolation >= 0.6) parts += T.partSecrecy(l)
            if (t != null && t.urgencyOrDeadline >= 0.6) parts += T.partUrgency(l)
            if (t != null && t.paymentOrTransferRequest >= 0.6) parts += T.partPayment(l)
            out += T.claimed(l, identityName(id, l), parts)
        } else if (t != null) {
            if (t.secrecyOrIsolation >= 0.6) out += T.secrecyOnly(l)
            if (t.threatOfArrestOrLegalAction >= 0.6) out += T.threatOnly(l)
        }
        if (r.hardOverride == HardOverride.PAYEE_FROM_CALL) out += T.payeeFromCall(l)
        if (r.rules.any { it.code == RuleCode.INSTITUTION_FROM_PERSONAL_MOBILE }) out += T.institutionFromPersonalMobile(l)
        r.rules.sortedByDescending { it.points }.forEach { hit ->
            when (hit.code) {
                RuleCode.NUMBER_ON_SCAM_LIST -> out += T.scamList(l)
                RuleCode.REMOTE_ACCESS_ACTIVE -> out += T.remoteAccess(l, hit.detail ?: "")
                RuleCode.PAYMENT_APP_DURING_CALL -> out += T.payAppDuringCall(l, hit.detail ?: "")
                RuleCode.NEW_PAYEE -> out += T.newPayee(l)
                RuleCode.AMOUNT_ABOVE_USUAL -> out += T.bigAmount(l, hit.detail ?: "")
                RuleCode.OTP_OR_COLLECT_DURING_CALL -> out += T.otpDuringCall(l)
                RuleCode.UNKNOWN_LIVE_CALL -> out += T.unknownCaller(l)
                RuleCode.SYNTHETIC_VOICE -> out += T.syntheticVoice(l)
                RuleCode.REPEATED_NEW_PAYEES -> out += T.repeatedNewPayees(l)
                RuleCode.ESCALATING_NEW_PAYEE_AMOUNTS -> out += T.escalating(l)
                else -> Unit
            }
        }
        return out.distinct()
    }

    private fun identityName(id: ClaimedIdentity, l: Lang) = T.identity(l, id)

    private fun mythBuster(id: ClaimedIdentity, l: Lang): String? = when (id) {
        ClaimedIdentity.CBI_OFFICER, ClaimedIdentity.POLICE -> T.mythPolice(l)
        ClaimedIdentity.BANK -> T.mythBank(l)
        ClaimedIdentity.COURIER -> T.mythCourier(l)
        ClaimedIdentity.FAMILY_MEMBER -> T.mythFamily(l)
        else -> null
    }
}

/** All user-facing copy, English and Hindi. Kept in one place so Om/Ishan can edit wording without touching logic. */
internal object T {
    private fun <A> pick(l: Lang, en: A, hi: A) = if (l == Lang.HI) hi else en

    fun nudgeHeadline(l: Lang) = pick(l, "This caller may be a scammer. Real police never ask for money on a call.", "यह कॉलर ठग हो सकता है। असली पुलिस कॉल पर पैसे नहीं मांगती।")
    fun pauseHeadline(l: Lang) = pick(l, "Wait. This payment looks like a scam.", "रुकिए। यह पेमेंट ठगी जैसा लग रहा है।")
    fun pauseGuardianHeadline(l: Lang) = pick(l, "STOP. This looks like a scam in progress.", "रुकिए! यह ठगी हो रही लगती है।")
    fun nudgeSpoken(l: Lang) = pick(l, "Careful. This caller may be a scammer. Real police never ask for money on a call.", "सावधान। यह कॉलर ठग हो सकता है। असली पुलिस कॉल पर पैसे नहीं मांगती।")
    fun pauseSpoken(l: Lang) = pick(l, "Please stop. This looks like a scam. Hang up and call your family on their saved number.", "कृपया रुकिए। यह ठगी लग रही है। कॉल काटिए और अपने परिवार को उनके सेव नंबर पर फ़ोन कीजिए।")
    fun callNow(l: Lang, name: String) = pick(l, "Call $name now", "$name को अभी कॉल करें")

    /** National cybercrime helpline number. */
    const val CYBER_HELPLINE = "1930"

    fun callHelpline(l: Lang) = pick(
        l,
        "Call $CYBER_HELPLINE (cybercrime helpline)",
        "$CYBER_HELPLINE (साइबर अपराध हेल्पलाइन) पर कॉल करें",
    )
    fun callbackVerify(l: Lang, name: String?) = pick(
        l,
        "Hang up and call ${name ?: "them"} on the saved number to check.",
        "कॉल काटिए और ${name ?: "उन्हें"} को सेव नंबर पर फ़ोन करके पूछिए।",
    )

    fun identity(l: Lang, id: ClaimedIdentity) = when (id) {
        ClaimedIdentity.CBI_OFFICER -> pick(l, "a CBI officer", "सीबीआई अधिकारी")
        ClaimedIdentity.POLICE -> pick(l, "the police", "पुलिस")
        ClaimedIdentity.BANK -> pick(l, "your bank", "आपका बैंक")
        ClaimedIdentity.COURIER -> pick(l, "a courier or customs officer", "कूरियर या कस्टम अधिकारी")
        ClaimedIdentity.FAMILY_MEMBER -> pick(l, "a family member", "परिवार का सदस्य")
        else -> pick(l, "an official", "कोई अधिकारी")
    }

    fun claimed(l: Lang, who: String, parts: List<String>): String {
        val base = pick(l, "The caller claimed to be $who", "कॉल करने वाले ने खुद को $who बताया")
        if (parts.isEmpty()) return base + "."
        val joined = parts.joinToString(pick(l, ", ", ", "))
        return pick(l, "$base and $joined.", "$base और $joined।")
    }

    fun partThreat(l: Lang) = pick(l, "threatened arrest or legal action", "गिरफ्तारी या कानूनी कार्रवाई की धमकी दी")
    fun partSecrecy(l: Lang) = pick(l, "told you not to tell anyone", "किसी को न बताने को कहा")
    fun partUrgency(l: Lang) = pick(l, "pushed you to act immediately", "तुरंत कदम उठाने का दबाव डाला")
    fun partPayment(l: Lang) = pick(l, "asked you to transfer money", "पैसे ट्रांसफर करने को कहा")
    fun secrecyOnly(l: Lang) = pick(l, "The caller told you to keep this secret or stay on the line.", "कॉलर ने इसे गुप्त रखने या कॉल पर बने रहने को कहा।")
    fun threatOnly(l: Lang) = pick(l, "The caller threatened arrest, a frozen account or legal action.", "कॉलर ने गिरफ्तारी, अकाउंट बंद होने या कानूनी कार्रवाई की धमकी दी।")
    fun payeeFromCall(l: Lang) = pick(l, "The account you are paying was read out to you on this call.", "आप जिस अकाउंट में पैसे भेज रहे हैं, उसकी जानकारी इसी कॉल पर बताई गई थी।")
    fun scamList(l: Lang) = pick(l, "This number has been reported as a scam number.", "यह नंबर ठगी वाले नंबर के रूप में रिपोर्ट किया गया है।")
    fun remoteAccess(l: Lang, app: String) = pick(l, "A screen-sharing app ($app) is running, so someone may be watching your phone.", "स्क्रीन शेयर करने वाला ऐप ($app) चल रहा है, कोई आपका फ़ोन देख सकता है।")
    fun payAppDuringCall(l: Lang, app: String) = pick(l, "You opened ${app.ifBlank { "a payment app" }} while this call is still live.", "यह कॉल चल रही है और आपने ${app.ifBlank { "पेमेंट ऐप" }} खोल लिया है।")
    fun newPayee(l: Lang) = pick(l, "You have never paid this person before.", "आपने इस व्यक्ति को पहले कभी पेमेंट नहीं किया है।")
    fun bigAmount(l: Lang, detail: String) = pick(l, "This amount is ${detail.ifBlank { "much higher than" }} your usual payment.", "यह रकम आपकी सामान्य रकम से ${detail.ifBlank { "बहुत ज़्यादा" }} है।")
    fun otpDuringCall(l: Lang) = pick(l, "An OTP or payment request arrived during this call.", "इस कॉल के दौरान OTP या पेमेंट रिक्वेस्ट आई।")
    fun unknownCaller(l: Lang) = pick(l, "You are on a live call with a number that is not in your contacts.", "आप ऐसे नंबर से कॉल पर हैं जो आपके कॉन्टैक्ट्स में नहीं है।")
    fun institutionFromPersonalMobile(l: Lang) = pick(
        l,
        "They claim to be an official, but this number is an ordinary mobile, not a 140 or 160 service line.",
        "वे खुद को अधिकारी बता रहे हैं, लेकिन यह नंबर साधारण मोबाइल है, 140 या 160 सेवा लाइन नहीं।",
    )
    fun repeatedNewPayees(l: Lang) = pick(l, "You are paying several different new accounts in a row, a common pattern in investment and task scams.", "आप लगातार कई नए अकाउंट में पैसे भेज रहे हैं, निवेश और टास्क ठगी में ऐसा ही होता है।")
    fun escalating(l: Lang) = pick(l, "Each payment is bigger than the last one.", "हर पेमेंट पिछले से बड़ा है।")
    fun syntheticVoice(l: Lang) = pick(l, "The caller's voice sounds artificial.", "कॉलर की आवाज़ बनावटी लग रही है।")

    fun mythPolice(l: Lang) = pick(l, "CBI and police never arrest anyone over a video call and never ask for money.", "सीबीआई और पुलिस वीडियो कॉल पर गिरफ्तारी नहीं करती और पैसे नहीं मांगती।")
    fun mythBank(l: Lang) = pick(l, "Banks never ask for your PIN or OTP, or ask you to install an app.", "बैंक कभी आपका PIN, OTP या कोई ऐप इंस्टॉल करने को नहीं कहता।")
    fun mythCourier(l: Lang) = pick(l, "Customs never calls about a parcel and asks for money.", "कस्टम पार्सल के बारे में कॉल करके पैसे नहीं मांगता।")
    fun mythFamily(l: Lang) = pick(l, "A cloned voice cannot answer your family safe word, or the real number.", "क्लोन की गई आवाज़ आपके परिवार का सीक्रेट शब्द या असली नंबर पर जवाब नहीं दे सकती।")

    fun questions(l: Lang) = listOf(
        pick(l, "Is the person on the call telling you to keep this secret or not to tell your family?", "क्या कॉल पर मौजूद व्यक्ति आपसे इसे गुप्त रखने या परिवार को न बताने को कह रहा है?"),
        pick(l, "Did they say you will be arrested, or your account frozen, if you don't pay now?", "क्या उन्होंने कहा कि अभी पैसे न दिए तो आपको गिरफ्तार किया जाएगा या अकाउंट बंद होगा?"),
        pick(l, "Are they asking you to stay on the call while you pay?", "क्या वे आपसे पेमेंट करते समय कॉल पर बने रहने को कह रहे हैं?"),
    )

    fun ifAnyYes(l: Lang) = pick(
        l,
        "This is exactly how scammers work. Government agencies never do this. Hang up and call your family on their saved number.",
        "ठग ठीक ऐसे ही काम करते हैं। सरकारी एजेंसियां कभी ऐसा नहीं करतीं। कॉल काटिए और परिवार को उनके सेव नंबर पर फ़ोन कीजिए।",
    )
}
