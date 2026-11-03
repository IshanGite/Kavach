package com.kavach.core.analysis

import com.kavach.core.config.RiskConfig
import com.kavach.core.llm.LlmClient
import com.kavach.core.llm.LlmRequest
import com.kavach.core.llm.Prompts
import com.kavach.core.model.CallChannel
import com.kavach.core.model.MessageVerdict
import com.kavach.core.model.ClaimedIdentity
import com.kavach.core.model.ScamType
import com.kavach.core.model.Tactics
import com.kavach.core.model.TacticsFindings
import com.kavach.core.orchestrator.AnalysisRequest
import kotlinx.coroutines.CancellationException

interface TranscriptAnalyst {
    suspend fun analyze(req: AnalysisRequest): TacticsFindings
}

interface MessageClassifier {
    suspend fun classify(atMs: Long, source: String, text: String): MessageVerdict
}

/** Offline analyst: always available, deterministic, injection-proof. */
object KeywordTranscriptAnalyst : TranscriptAnalyst {
    override suspend fun analyze(req: AnalysisRequest) = KeywordAnalyst.analyze(req.windowText)
}

/**
 * LLM interprets, code keeps it honest:
 *  - keyword analyst always runs; its score is a floor under the LLM's (a manipulated or confused model cannot zero it)
 *  - LLM failure or garbage output degrades to the keyword result instead of failing
 *  - evidence quotes / payee details are verified against the transcript when they enter the session
 */
class HybridTranscriptAnalyst(
    private val llm: LlmClient?,
    private val cfg: RiskConfig,
    private val compactPrompt: () -> Boolean = { false },
) : TranscriptAnalyst {

    /** Source label of the last result, for the settings screen. */
    @Volatile
    var lastSource: String = "none"
        private set

    override suspend fun analyze(req: AnalysisRequest): TacticsFindings {
        val kw = KeywordAnalyst.analyze(req.windowText)
        val fromLlm = llm?.let { askLlm(it, req) }
        val merged = if (fromLlm == null) kw else merge(fromLlm, kw)
        lastSource = merged.source
        return merged
    }

    private suspend fun askLlm(client: LlmClient, req: AnalysisRequest): TacticsFindings? = try {
        val res = client.analyze(
            LlmRequest(
                system = if (compactPrompt()) Prompts.TRANSCRIPT_SYSTEM_COMPACT else Prompts.TRANSCRIPT_SYSTEM,
                user = Prompts.transcriptUser(req.windowText, req.callDurationMin, req.callerInContacts, req.channel == CallChannel.VOIP),
                maxTokens = 300,
            ),
        )
        FindingsParser.parseFindings(res.text, res.provider)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** An LLM tactic score above this needs the rule lexicon to see the same tactic. */
    private val UNCORROBORATED_CAP = 0.4

    private fun ground(llm: Double, kw: Double) = if (kw > 0.0) llm else minOf(llm, UNCORROBORATED_CAP)

    /**
     * Grounding: the LLM may SHARPEN a signal the rules also see, but it cannot INVENT coercion. Measured need: a 4B model in terse mode
     * answered risk 75 with "authority 0.8, threat 0.8" for an electrician asking for Rs 800, on three different benign calls.
     * So (1) a tactic above [UNCORROBORATED_CAP] needs a lexicon hit for that tactic, and (2) the headline risk may not exceed what the
     * LLM's own (grounded) tactic scores justify under the same combination rule the keyword analyst uses. The keyword score stays a floor.
     */
    internal fun merge(llm: TacticsFindings, kw: TacticsFindings): TacticsFindings {
        val f = cfg.keywordFloorFraction
        val a = llm.tactics; val k = kw.tactics
        val grounded = Tactics(
            ground(a.authorityImpersonation, k.authorityImpersonation), ground(a.threatOfArrestOrLegalAction, k.threatOfArrestOrLegalAction),
            ground(a.urgencyOrDeadline, k.urgencyOrDeadline), ground(a.secrecyOrIsolation, k.secrecyOrIsolation),
            ground(a.paymentOrTransferRequest, k.paymentOrTransferRequest), ground(a.remoteAccessOrOtpRequest, k.remoteAccessOrOtpRequest),
            ground(a.emotionalEmergency, k.emotionalEmergency),
        )
        val floorRisk = (kw.llmRisk * f).toInt()
        val floorTactics = with(k) {
            copy(
                authorityImpersonation = authorityImpersonation * f, threatOfArrestOrLegalAction = threatOfArrestOrLegalAction * f,
                urgencyOrDeadline = urgencyOrDeadline * f, secrecyOrIsolation = secrecyOrIsolation * f,
                paymentOrTransferRequest = paymentOrTransferRequest * f, remoteAccessOrOtpRequest = remoteAccessOrOtpRequest * f,
                emotionalEmergency = emotionalEmergency * f,
            )
        }
        val tactics = grounded.max(floorTactics)
        val supported = KeywordAnalyst.riskOf(grounded) + GROUNDING_SLACK
        val risk = maxOf(minOf(llm.llmRisk, supported), floorRisk)
        // An identity or scam type the rules do not support is kept only as a label for the user-facing reason text when the risk is real.
        val keepLabels = risk >= 35
        return llm.copy(
            tactics = tactics,
            llmRisk = risk,
            scamType = if (llm.scamType != ScamType.NONE && keepLabels) llm.scamType else kw.scamType,
            claimedIdentity = if (llm.claimedIdentity != ClaimedIdentity.NONE && keepLabels) llm.claimedIdentity else kw.claimedIdentity,
            evidenceQuotes = (llm.evidenceQuotes + kw.evidenceQuotes).distinct().take(8),
            payeeDetailsMentioned = (llm.payeeDetailsMentioned + kw.payeeDetailsMentioned).distinct().take(8),
            source = if (kw.llmRisk > risk) "${llm.source}+keywords" else if (risk < llm.llmRisk) "${llm.source} (grounded)" else llm.source,
        )
    }

    private companion object { const val GROUNDING_SLACK = 10 }
}

/** Rules first (instant), LLM second if available; the more suspicious verdict wins. */
class HybridMessageClassifier(private val llm: LlmClient?) : MessageClassifier {
    override suspend fun classify(atMs: Long, source: String, text: String): MessageVerdict {
        val rules = MessageAnalyst.classify(atMs, source, text)
        val fromLlm = llm?.let {
            try {
                val res = it.analyze(LlmRequest(Prompts.MESSAGE_SYSTEM, Prompts.messageUser(source, text), maxTokens = 150))
                FindingsParser.parseMessageVerdict(res.text, atMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        return if (fromLlm != null && fromLlm.isScam && fromLlm.confidence > rules.confidence) fromLlm else rules
    }
}
