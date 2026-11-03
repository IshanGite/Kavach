package com.kavach.core.sim

import com.kavach.core.model.*

/** Step-by-step "why did it decide that" view. Used for failing evals, tuning on stage, and the judges' Q&A. */
object TraceExplainer {
    private fun label(e: KavachEvent): String = when (e) {
        is CallStarted -> "CallStarted ${e.number}${if (e.isInContacts) " (contact ${e.contactName})" else " (unknown)"}${if (e.channel == CallChannel.VOIP) " VoIP" else ""}"
        is CallEnded -> "CallEnded ${e.durationSec}s"
        is TranscriptChunk -> "Transcript \"${e.text.take(60)}${if (e.text.length > 60) "…" else ""}\""
        is PaymentIntent -> "Payment ${e.app} ${e.stage}${e.amountRupees?.let { " ₹${it.toLong()}" } ?: ""}${e.payee?.let { " → $it" } ?: ""}"
        is FindingsReady -> "Findings[${e.findings.source}] risk=${e.findings.llmRisk} ${e.findings.scamType}/${e.findings.claimedIdentity}"
        is MessageVerdict -> "MessageVerdict scam=${e.isScam} ${e.type} ${"%.2f".format(e.confidence)}"
        else -> e::class.simpleName ?: "event"
    }

    fun render(t: RunTrace, onlyChanges: Boolean = true): String = buildString {
        appendLine("--- ${t.scenario.id} (${t.scenario.kind}) analyst=${t.scenario.analyst}")
        var last = ""
        for (s in t.steps) {
            if (s.event is Tick && onlyChanges) continue
            val r = s.decision.result
            val rules = r.rules.joinToString(",") { "${it.code.name.lowercase()}+${it.points}" }
            val tail = buildString {
                r.hardOverride?.let { append(" OVERRIDE=$it") }
                if (r.guards.isNotEmpty()) append(" guards=${r.guards.joinToString { it.name.lowercase() }}")
                if (s.decision.effects.isNotEmpty()) append(" effects=${s.decision.effects.joinToString { it::class.simpleName ?: "?" }}")
            }
            val line = "t=%4ds C=%3d L=%4s R=%3d tier=%d %-12s [%s]%s".format(
                s.t, r.contextScore, r.languageScore?.toString() ?: "-", r.risk, s.decision.tier.level, s.decision.state, rules, tail,
            )
            if (!onlyChanges || line.substringAfter("C=") != last) appendLine("$line   <- ${label(s.event)}")
            last = line.substringAfter("C=")
        }
    }
}
