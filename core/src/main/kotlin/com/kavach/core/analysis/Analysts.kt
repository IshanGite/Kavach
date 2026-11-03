package com.kavach.core.analysis

import com.kavach.core.config.RiskConfig
import com.kavach.core.llm.LlmClient
import com.kavach.core.llm.LlmRequest
import com.kavach.core.llm.Prompts
import com.kavach.core.model.CallChannel
import com.kavach.core.model.MessageVerdict
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
                system = Prompts.TRANSCRIPT_SYSTEM,
                user = Prompts.transcriptUser(req.windowText, req.callDurationMin, req.callerInContacts, req.channel == CallChannel.VOIP),
            ),
        )
        FindingsParser.parseFindings(res.text, res.provider)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    internal fun merge(llm: TacticsFindings, kw: TacticsFindings): TacticsFindings {
        val f = cfg.keywordFloorFraction
        val floorRisk = (kw.llmRisk * f).toInt()
        val floorTactics = with(kw.tactics) {
            copy(
                authorityImpersonation = authorityImpersonation * f,
                threatOfArrestOrLegalAction = threatOfArrestOrLegalAction * f,
                urgencyOrDeadline = urgencyOrDeadline * f,
                secrecyOrIsolation = secrecyOrIsolation * f,
                paymentOrTransferRequest = paymentOrTransferRequest * f,
                remoteAccessOrOtpRequest = remoteAccessOrOtpRequest * f,
                emotionalEmergency = emotionalEmergency * f,
            )
        }
        return llm.copy(
            tactics = llm.tactics.max(floorTactics),
            llmRisk = maxOf(llm.llmRisk, floorRisk),
            scamType = if (llm.scamType != com.kavach.core.model.ScamType.NONE) llm.scamType else kw.scamType,
            claimedIdentity = if (llm.claimedIdentity != com.kavach.core.model.ClaimedIdentity.NONE) llm.claimedIdentity else kw.claimedIdentity,
            evidenceQuotes = (llm.evidenceQuotes + kw.evidenceQuotes).distinct().take(8),
            payeeDetailsMentioned = (llm.payeeDetailsMentioned + kw.payeeDetailsMentioned).distinct().take(8),
            source = if (kw.llmRisk > llm.llmRisk) "${llm.source}+keywords" else llm.source,
        )
    }
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
