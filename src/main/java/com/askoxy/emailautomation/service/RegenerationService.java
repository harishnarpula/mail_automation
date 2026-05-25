package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.agent.*;
import com.askoxy.emailautomation.dto.GeneratedEmailDto;
import com.askoxy.emailautomation.entity.EmailApprovalSession;
import com.askoxy.emailautomation.entity.UploadedFile;
import com.askoxy.emailautomation.repository.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegenerationService {

    private final RetrievalService retrievalService;
    private final UploadedFileRepository uploadedFileRepository;

    private final ClientIntelligenceAgent clientIntelligenceAgent;
    private final OpportunityMatchingAgent opportunityMatchingAgent;
    private final StrategyAgent strategyAgent;
    private final EmailGenerationAgent emailGenerationAgent;
    private final ComplianceAgent complianceAgent;
    private final ReplyGenerationAgent replyGenerationAgent;

    public GeneratedEmailDto regenerate(EmailApprovalSession session) {
        log.info("[Regeneration] Starting attempt #{} for sessionId={} sessionType={}",
                session.getAttemptCount(), session.getSessionId(), session.getSessionType());

        // Log all fields we depend on — catches null issues immediately
        log.info("[Regeneration] clientName='{}' clientEmail='{}'",
                session.getClientName(), session.getClientEmail());
        log.info("[Regeneration] clientReplyContent='{}'",
                session.getClientReplyContent() != null
                        ? session.getClientReplyContent().substring(0,
                        Math.min(200, session.getClientReplyContent().length()))
                        : "NULL");
        log.info("[Regeneration] accumulatedFeedback='{}'", session.getAccumulatedFeedback());
        log.info("[Regeneration] currentSubject='{}'", session.getCurrentSubject());
        log.info("[Regeneration] fileId='{}'", session.getFileId());

        String vectorStoreId = resolveVectorStoreId(session.getFileId());
        log.info("[Regeneration] Resolved vectorStoreId='{}'", vectorStoreId);

        if ("CLIENT_REPLY".equals(session.getSessionType())) {
            return regenerateReply(session, vectorStoreId);
        } else {
            return regenerateCampaignEmail(session, vectorStoreId);
        }
    }

    // ── CAMPAIGN regeneration ─────────────────────────────────────────────────

    private GeneratedEmailDto regenerateCampaignEmail(EmailApprovalSession session, String vectorStoreId) {
        String feedback = session.getAccumulatedFeedback() == null
                ? "" : session.getAccumulatedFeedback().trim();

        log.info("[Regeneration] CAMPAIGN pipeline starting — feedback='{}'", feedback);

        String ragContext = retrievalService.retrieve(
                "company overview products services capabilities what we build what we offer",
                vectorStoreId, 10);

        log.info("[Regeneration] RAG context retrieved — length={} chars",
                ragContext != null ? ragContext.length() : 0);

        String clientIntel  = clientIntelligenceAgent.analyze(ragContext, session.getClientName(), feedback);
        String opportunity  = opportunityMatchingAgent.findOpportunity(session.getClientName(), clientIntel, feedback);
        String strategy     = strategyAgent.buildStrategy(session.getClientName(), opportunity, feedback);
        GeneratedEmailDto raw = emailGenerationAgent.generateEmail(
                session.getClientName(), clientIntel, strategy, ragContext, feedback);
        GeneratedEmailDto finalEmail = complianceAgent.reviewAndRefine(raw, feedback);

        log.info("[Regeneration] CAMPAIGN attempt #{} completed — subject='{}'",
                session.getAttemptCount(), finalEmail.getSubject());
        return finalEmail;
    }

    // ── CLIENT_REPLY regeneration ─────────────────────────────────────────────

    private GeneratedEmailDto regenerateReply(EmailApprovalSession session, String vectorStoreId) {
        String feedback = session.getAccumulatedFeedback() == null
                ? "" : session.getAccumulatedFeedback().trim();

        log.info("[Regeneration] CLIENT_REPLY pipeline starting — feedback='{}'", feedback);

        // CRITICAL: clientReplyContent must not be null — it's what the client originally said
        // If it's null here, the reply will be generic/wrong regardless of feedback
        String clientReplyContent = session.getClientReplyContent();
        if (clientReplyContent == null || clientReplyContent.isBlank()) {
            log.error("[Regeneration] clientReplyContent is NULL for sessionId={} — " +
                            "reply will lack client context. Check ClientReplyProcessorService.setClientReplyContent()",
                    session.getSessionId());
            clientReplyContent = "(client reply content unavailable — please re-read the thread)";
        }

        String ragContext = retrievalService.retrieve(
                "company overview products services capabilities what we build what we offer",
                vectorStoreId, 10);

        log.info("[Regeneration] RAG context retrieved — length={} chars",
                ragContext != null ? ragContext.length() : 0);
        log.info("[Regeneration] Calling ReplyGenerationAgent — clientName='{}' subject='{}' feedbackLength={}",
                session.getClientName(), session.getCurrentSubject(),
                feedback.length());

        GeneratedEmailDto raw = replyGenerationAgent.generateReply(
                session.getClientName(),
                clientReplyContent,
                session.getCurrentSubject(),
                ragContext,
                feedback          // ← admin feedback passed here
        );

        log.info("[Regeneration] ReplyGenerationAgent returned — subject='{}' bodyLength={}",
                raw.getSubject(), raw.getBody() != null ? raw.getBody().length() : 0);

        // Pass feedback to compliance too so it doesn't strip changes made per feedback
        GeneratedEmailDto finalReply = complianceAgent.reviewAndRefine(raw, feedback, true);

        log.info("[Regeneration] CLIENT_REPLY attempt #{} completed — finalSubject='{}'",
                session.getAttemptCount(), finalReply.getSubject());
        return finalReply;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveVectorStoreId(String fileId) {
        if (fileId != null && !fileId.isBlank()) {
            String primaryFileId = fileId.split(",")[0].trim();
            return uploadedFileRepository.findByFileId(primaryFileId)
                    .map(UploadedFile::getVectorStoreId)
                    .filter(v -> v != null && !v.isBlank())
                    .orElseGet(this::latestVectorStoreId);
        }
        return latestVectorStoreId();
    }

    private String latestVectorStoreId() {
        return uploadedFileRepository
                .findTopByUploadStatusOrderByCreatedAtDesc("COMPLETED")
                .map(UploadedFile::getVectorStoreId)
                .orElseThrow(() -> new RuntimeException(
                        "[Regeneration] No completed file found for RAG retrieval"));
    }
}