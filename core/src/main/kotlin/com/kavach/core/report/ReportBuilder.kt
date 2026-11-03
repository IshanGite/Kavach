package com.kavach.core.report

import com.kavach.core.model.CallChannel
import com.kavach.core.model.ClaimedIdentity
import com.kavach.core.model.ScamType
import com.kavach.core.state.SessionState
import com.kavach.core.state.TimelineEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PortalField(val label: String, val value: String)

data class ReportActions(
    val helpline: String = "1930",
    val portalUrl: String = "https://cybercrime.gov.in",
    /** Sanchar Saathi's Chakshu: report suspected fraud communication when no money was lost. */
    val chakshuUrl: String = "https://sancharsaathi.gov.in/sfc/",
    /** When money has gone, calling 1930 fast gives the best chance of the bank freezing the funds. */
    val callFirst: Boolean,
)

data class ComplaintReport(
    val category: String,
    val subType: String,
    val moneyLost: Boolean,
    val incidentTime: String,
    val contactDuration: String,
    val suspectNumbers: List<String>,
    val suspectPayees: List<String>,
    val claimedAgency: String?,
    val transaction: List<PortalField>,
    val timeline: List<TimelineEntry>,
    val timelineText: List<String>,
    val evidenceQuotes: List<String>,
    val narrative: String,
    val fields: List<PortalField>,
    val actions: ReportActions,
)

/**
 * Builds the complaint from the session. Facts come from sensors and the context store; only the narrative is prose, and it
 * is always shown to the user for review before anything is shared, since it is submitted to police in their name.
 */
object ReportBuilder {
    private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)

    fun subTypeLabel(t: ScamType) = when (t) {
        ScamType.DIGITAL_ARREST -> "Impersonation / digital arrest"
        ScamType.VOICE_CLONE_FAMILY -> "Impersonation of a relative (suspected voice clone)"
        ScamType.KYC_BANK -> "Fake bank / KYC fraud"
        ScamType.COURIER -> "Fake courier / customs fraud"
        ScamType.REFUND -> "Refund / collect-request fraud"
        ScamType.INVESTMENT -> "Investment / task fraud"
        ScamType.NONE -> "Suspected online financial fraud"
    }

    private fun agencyLabel(id: ClaimedIdentity) = when (id) {
        ClaimedIdentity.CBI_OFFICER -> "CBI"
        ClaimedIdentity.POLICE -> "Police / cyber crime branch"
        ClaimedIdentity.BANK -> "Bank / RBI"
        ClaimedIdentity.COURIER -> "Courier / customs"
        ClaimedIdentity.FAMILY_MEMBER -> "A family member"
        ClaimedIdentity.OTHER -> "An official"
        ClaimedIdentity.NONE -> null
    }

    fun build(s: SessionState, moneySent: Boolean, userName: String = "I", zone: ZoneId = ZoneId.of("Asia/Kolkata")): ComplaintReport {
        val call = s.call
        val startMs = call?.atMs ?: s.timeline.firstOrNull()?.atMs ?: s.nowMs
        val incidentTime = dateFmt.format(Instant.ofEpochMilli(startMs).atZone(zone))
        val durSec = s.callDurationSec ?: (s.callDurationMs() / 1000)
        val duration = if (call == null) "n/a" else "${durSec / 60} min ${durSec % 60} s"

        val numbers = listOfNotNull(call?.number)
        val payees = (s.spokenPayees + listOfNotNull(s.intent?.payee?.lowercase())).distinct()
        val agency = agencyLabel(s.claimedIdentity)
        val intent = s.intent
        val amount = intent?.amountRupees ?: s.debitAfterIntentRupees

        val tx = buildList {
            if (moneySent || s.debitAfterIntentRupees != null || s.paymentProceededAtMs != null) {
                amount?.let { add(PortalField("Amount", "₹${it.toLong()}")) }
                intent?.app?.takeIf { it != "collect-request" }?.let { add(PortalField("Payment app", it)) }
                intent?.payee?.let { add(PortalField("Payee (as shown in app)", it)) }
                add(PortalField("UTR / transaction ID", "(copy from your bank debit SMS or app history)"))
            }
        }

        val timelineText = s.timeline.map { "${DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH).format(Instant.ofEpochMilli(it.atMs).atZone(zone))}  ${it.label}" }
        val narrative = narrative(s, incidentTime, duration, agency, moneySent, amount, userName)

        val fields = buildList {
            add(PortalField("Incident category", "Online financial fraud"))
            add(PortalField("Sub-category", subTypeLabel(s.scamTypeSeen)))
            add(PortalField("Date and time of incident", incidentTime))
            add(PortalField("Duration of contact", duration))
            if (numbers.isNotEmpty()) add(PortalField("Suspect phone number(s)", numbers.joinToString()))
            if (payees.isNotEmpty()) add(PortalField("Suspect UPI ID / account number(s)", payees.joinToString()))
            agency?.let { add(PortalField("Identity claimed by suspect", it)) }
            addAll(tx)
            add(PortalField("Brief description", narrative))
        }

        return ComplaintReport(
            category = "Online financial fraud",
            subType = subTypeLabel(s.scamTypeSeen),
            moneyLost = moneySent,
            incidentTime = incidentTime,
            contactDuration = duration,
            suspectNumbers = numbers,
            suspectPayees = payees,
            claimedAgency = agency,
            transaction = tx,
            timeline = s.timeline,
            timelineText = timelineText,
            evidenceQuotes = s.evidenceQuotes,
            narrative = narrative,
            fields = fields,
            actions = ReportActions(callFirst = moneySent),
        )
    }

    private fun narrative(s: SessionState, whenText: String, duration: String, agency: String?, moneySent: Boolean, amount: Double?, who: String): String {
        val call = s.call
        val sb = StringBuilder()
        val medium = when {
            call == null -> "a contact"
            call.channel == CallChannel.VOIP -> "a WhatsApp/VoIP call"
            else -> "a phone call"
        }
        sb.append("On $whenText, $who received $medium")
        call?.let { sb.append(" from ${it.number}, which is not saved in my contacts") }
        sb.append(". It lasted about $duration. ")
        if (agency != null) sb.append("The caller claimed to be from: $agency. ")
        if (s.evidenceQuotes.isNotEmpty()) {
            sb.append("During the call they said things such as: ")
            sb.append(s.evidenceQuotes.take(4).joinToString("; ") { "\"$it\"" })
            sb.append(". ")
        }
        val intent = s.intent
        if (intent != null) {
            sb.append("I was then led to open ${intent.app.takeIf { it != "collect-request" } ?: "a payment request"}")
            amount?.let { sb.append(" to pay ₹${it.toLong()}") }
            intent.payee?.let { sb.append(" to $it") }
            sb.append(". ")
        }
        sb.append(if (moneySent) "Money was transferred. I am reporting this immediately so the funds can be frozen. " else "I did not complete a payment, but I am reporting this attempt. ")
        sb.append("The above timeline and call excerpts were captured automatically by the Kavach app on my phone.")
        return sb.toString()
    }

    /** Plain-text version for the share sheet and for the PDF renderer in the app. */
    fun renderText(r: ComplaintReport): String = buildString {
        appendLine("KAVACH COMPLAINT SUMMARY")
        if (r.actions.callFirst) appendLine("MONEY WAS SENT: CALL ${r.actions.helpline} NOW, then file at ${r.actions.portalUrl}")
        else appendLine("File at ${r.actions.portalUrl} or report the number at ${r.actions.chakshuUrl}")
        appendLine()
        r.fields.forEach { appendLine("${it.label}: ${it.value}"); appendLine() }
        if (r.timelineText.isNotEmpty()) {
            appendLine("TIMELINE")
            r.timelineText.forEach { appendLine(it) }
        }
    }.trimEnd()
}
