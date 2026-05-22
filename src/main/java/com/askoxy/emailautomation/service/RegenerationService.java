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

    // Campaign email agents
    private final ClientIntelligenceAgent clientIntelligenceAgent;
    private final OpportunityMatchingAgent opportunityMatchingAgent;
    private final StrategyAgent strategyAgent;
    private final EmailGenerationAgent emailGenerationAgent;
    private final ComplianceAgent complianceAgent;

    // Client reply agent
    private final ReplyGenerationAgent replyGenerationAgent;

    /**
     * Routes regeneration based on sessionType.
     *
     * CAMPAIGN      → full 5-agent pipeline (same as initial generation)
     * CLIENT_REPLY  → ReplyGenerationAgent + ComplianceAgent (uses client's reply as context)
     */
    public GeneratedEmailDto regenerate(EmailApprovalSession session) {
        log.info("[Regeneration] Starting attempt #{} for sessionId={} sessionType={}",
                session.getAttemptCount(), session.getSessionId(), session.getSessionType());

        String vectorStoreId = resolveVectorStoreId(session.getFileId());

        if ("CLIENT_REPLY".equals(session.getSessionType())) {
            return regenerateReply(session, vectorStoreId);
        } else {
            return regenerateCampaignEmail(session, vectorStoreId);
        }
    }

    // ─── CAMPAIGN regeneration — full 5-agent pipeline ──────────────────────────

    private GeneratedEmailDto regenerateCampaignEmail(EmailApprovalSession session, String vectorStoreId) {
        String feedback = session.getAccumulatedFeedback() == null
                ? "" : session.getAccumulatedFeedback().trim();

        String ragContext = retrievalService.retrieve(
                "company overview products services capabilities what we build what we offer",
                vectorStoreId, 10);

        String clientIntel   = clientIntelligenceAgent.analyze(ragContext, session.getClientName(), feedback);
        String opportunity   = opportunityMatchingAgent.findOpportunity(session.getClientName(), clientIntel, feedback);
        String strategy      = strategyAgent.buildStrategy(session.getClientName(), opportunity, feedback);
        GeneratedEmailDto raw = emailGenerationAgent.generateEmail(
                session.getClientName(), clientIntel, strategy, ragContext, feedback);
        GeneratedEmailDto finalEmail = complianceAgent.reviewAndRefine(raw, feedback);

        log.info("[Regeneration] CAMPAIGN attempt #{} completed for sessionId={}",
                session.getAttemptCount(), session.getSessionId());
        return finalEmail;
    }

    // ─── CLIENT_REPLY regeneration — reply agents only ───────────────────────────

    private GeneratedEmailDto regenerateReply(EmailApprovalSession session, String vectorStoreId) {
        String feedback = session.getAccumulatedFeedback() == null
                ? "" : session.getAccumulatedFeedback().trim();

        String ragContext = retrievalService.retrieve(
                "company overview products services capabilities what we build what we offer",
                vectorStoreId, 10);

        // clientReplyContent is the original message from the client that triggered this session
        String clientReplyContent = session.getClientReplyContent() != null
                ? session.getClientReplyContent()
                : "(client reply not available)";

        GeneratedEmailDto raw = replyGenerationAgent.generateReply(
                session.getClientName(),
                clientReplyContent,
                session.getCurrentSubject(),
                ragContext,
                feedback
        );

        GeneratedEmailDto finalReply = complianceAgent.reviewAndRefine(raw, feedback, true);

        log.info("[Regeneration] CLIENT_REPLY attempt #{} completed for sessionId={}",
                session.getAttemptCount(), session.getSessionId());
        return finalReply;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

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
