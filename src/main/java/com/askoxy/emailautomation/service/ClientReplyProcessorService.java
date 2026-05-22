package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.agent.ComplianceAgent;
import com.askoxy.emailautomation.agent.ReplyGenerationAgent;
import com.askoxy.emailautomation.dto.GeneratedEmailDto;
import com.askoxy.emailautomation.entity.EmailApprovalSession;
import com.askoxy.emailautomation.repository.EmailApprovalSessionRepository;
import com.askoxy.emailautomation.repository.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Handles client replies in a continuous loop:
 * 1) immediate generation when no active pending approval exists
 * 2) queueing when a pending/re-generating session already exists
 * 3) scheduled draining of queued replies after pending session is resolved
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientReplyProcessorService {

    private final EmailApprovalSessionRepository sessionRepository;
    private final RetrievalService retrievalService;
    private final UploadedFileRepository uploadedFileRepository;
    private final ReplyGenerationAgent replyGenerationAgent;
    private final ComplianceAgent complianceAgent;
    private final WhatsAppNotificationService whatsAppNotificationService;

    @Transactional
    public void processClientReply(String clientEmail,
                                   String subject,
                                   String replyBody,
                                   String messageId,
                                   String inReplyTo,
                                   String references) {

        log.info("[ClientReplyProcessor] Processing reply from clientEmail={} subject={}", clientEmail, subject);

        if (messageId != null && sessionRepository.existsByProcessedMessageId(messageId)) {
            log.warn("[ClientReplyProcessor] messageId={} already processed. Skipping.", messageId);
            return;
        }

        EmailApprovalSession lastApprovedSession = findLastApprovedSession(clientEmail);
        if (lastApprovedSession == null) {
            log.warn("[ClientReplyProcessor] No approved session found for clientEmail={}. Cannot process reply.",
                    clientEmail);
            return;
        }

        boolean hasActivePendingSession = sessionRepository
                .findTopByClientEmailAndStatusInOrderByCreatedAtDesc(
                        clientEmail, List.of("PENDING_APPROVAL", "REGENERATING"))
                .isPresent();

        if (hasActivePendingSession) {
            queueClientReply(lastApprovedSession, subject, replyBody, messageId, inReplyTo);
            return;
        }

        EmailApprovalSession immediateSession = new EmailApprovalSession();
        immediateSession.setClientName(lastApprovedSession.getClientName());
        immediateSession.setClientEmail(clientEmail);
        immediateSession.setFileId(lastApprovedSession.getFileId());
        immediateSession.setSessionType("CLIENT_REPLY");
        immediateSession.setCurrentSubject(subject);
        immediateSession.setClientReplyContent(replyBody);
        immediateSession.setProcessedMessageId(messageId);
        immediateSession.setEmailThreadId(inReplyTo != null ? inReplyTo : messageId);
        immediateSession.setStatus("REGENERATING"); // transient state during generation

        generateReplyAndMoveToPendingApproval(immediateSession, lastApprovedSession, null);
    }

    @Scheduled(fixedDelayString = "${app.client-reply.queue-interval-ms:15000}")
    @Transactional
    public void processQueuedReplies() {
        List<EmailApprovalSession> queuedItems = sessionRepository
                .findTop20ByStatusAndSessionTypeOrderByCreatedAtAsc("QUEUED", "CLIENT_REPLY");
        if (queuedItems.isEmpty()) {
            return;
        }

        EmailApprovalSession queued = null;
        for (EmailApprovalSession item : queuedItems) {
            boolean hasActivePendingSession = sessionRepository
                    .findTopByClientEmailAndStatusInOrderByCreatedAtDesc(
                            item.getClientEmail(), List.of("PENDING_APPROVAL", "REGENERATING"))
                    .isPresent();
            if (!hasActivePendingSession) {
                queued = item;
                break;
            }
        }

        if (queued == null) {
            return;
        }

        EmailApprovalSession lastApprovedSession = findLastApprovedSession(queued.getClientEmail());
        if (lastApprovedSession == null) {
            log.warn("[ClientReplyProcessor] No approved context for queued session={} client={}. Expiring queued item.",
                    queued.getSessionId(), queued.getClientEmail());
            queued.setStatus("EXPIRED");
            sessionRepository.save(queued);
            return;
        }

        generateReplyAndMoveToPendingApproval(queued, lastApprovedSession, queued.getAccumulatedFeedback());
    }

    private void queueClientReply(EmailApprovalSession lastApprovedSession,
                                  String subject,
                                  String replyBody,
                                  String messageId,
                                  String inReplyTo) {
        EmailApprovalSession queued = new EmailApprovalSession();
        queued.setClientName(lastApprovedSession.getClientName());
        queued.setClientEmail(lastApprovedSession.getClientEmail());
        queued.setFileId(lastApprovedSession.getFileId());
        queued.setSessionType("CLIENT_REPLY");
        queued.setCurrentSubject(subject);
        queued.setClientReplyContent(replyBody);
        queued.setProcessedMessageId(messageId);
        queued.setEmailThreadId(inReplyTo != null ? inReplyTo : messageId);
        queued.setStatus("QUEUED");

        EmailApprovalSession saved = sessionRepository.save(queued);
        log.info("[ClientReplyProcessor] Queued client reply session={} client={} messageId={}",
                saved.getSessionId(), saved.getClientEmail(), messageId);
    }

    private void generateReplyAndMoveToPendingApproval(EmailApprovalSession targetSession,
                                                       EmailApprovalSession lastApprovedSession,
                                                       String feedbackHistory) {
        String clientName = lastApprovedSession.getClientName();
        String fileId = lastApprovedSession.getFileId();
        String clientReplyContent = targetSession.getClientReplyContent() != null
                ? targetSession.getClientReplyContent()
                : "(empty client reply)";

        String vectorStoreId = resolveVectorStoreId(fileId, lastApprovedSession);
        String companyContext = retrievalService.retrieve(
                "company overview products services capabilities what we build what we offer",
                vectorStoreId, 10);

        String originalSubject = lastApprovedSession.getCurrentSubject() != null
                ? lastApprovedSession.getCurrentSubject()
                : (targetSession.getCurrentSubject() != null ? targetSession.getCurrentSubject() : "Re: Conversation");

        GeneratedEmailDto rawReply = replyGenerationAgent.generateReply(
                clientName,
                clientReplyContent,
                originalSubject,
                companyContext,
                feedbackHistory
        );

        GeneratedEmailDto finalReply = complianceAgent.reviewAndRefine(rawReply, feedbackHistory, true);

        targetSession.setClientName(clientName);
        targetSession.setClientEmail(lastApprovedSession.getClientEmail());
        targetSession.setCurrentSubject(finalReply.getSubject());
        targetSession.setCurrentBody(finalReply.getBody());
        targetSession.setFileId(fileId);
        targetSession.setSessionType("CLIENT_REPLY");
        targetSession.setStatus("PENDING_APPROVAL");

        EmailApprovalSession savedSession = sessionRepository.save(targetSession);
        log.info("[ClientReplyProcessor] Created CLIENT_REPLY session={} for client={}",
                savedSession.getSessionId(), savedSession.getClientEmail());

        whatsAppNotificationService.sendReplyForApproval(savedSession);
    }

    /**
     * Called by regeneration flow for CLIENT_REPLY sessions.
     */
    public GeneratedEmailDto regenerateReply(EmailApprovalSession session, String vectorStoreId) {
        String companyContext = retrievalService.retrieve(
                "company overview products services capabilities what we build what we offer",
                vectorStoreId, 10);

        GeneratedEmailDto rawReply = replyGenerationAgent.generateReply(
                session.getClientName(),
                session.getClientReplyContent(),
                session.getCurrentSubject(),
                companyContext,
                session.getAccumulatedFeedback()
        );

        return complianceAgent.reviewAndRefine(rawReply, session.getAccumulatedFeedback(), true);
    }

    private EmailApprovalSession findLastApprovedSession(String clientEmail) {
        return sessionRepository
                .findTopByClientEmailAndStatusAndSessionTypeOrNullOrderByLastUpdatedAtDesc(
                        clientEmail, "APPROVED", "CAMPAIGN")
                .or(() -> sessionRepository
                        .findTopByClientEmailAndStatusAndSessionTypeOrNullOrderByLastUpdatedAtDesc(
                                clientEmail, "APPROVED", "CLIENT_REPLY"))
                .orElse(null);
    }

    private String resolveVectorStoreId(String fileId, EmailApprovalSession session) {
        try {
            if (fileId != null && !fileId.isBlank()) {
                return uploadedFileRepository.findByFileId(fileId)
                        .map(f -> f.getVectorStoreId())
                        .filter(v -> v != null && !v.isBlank())
                        .orElseGet(() -> uploadedFileRepository
                                .findTopByUploadStatusOrderByCreatedAtDesc("COMPLETED")
                                .map(f -> f.getVectorStoreId())
                                .orElse(fileId));
            }
        } catch (Exception e) {
            log.warn("[ClientReplyProcessor] Could not resolve vectorStoreId for fileId={}", fileId);
        }
        return uploadedFileRepository
                .findTopByUploadStatusOrderByCreatedAtDesc("COMPLETED")
                .map(f -> f.getVectorStoreId())
                .orElse(fileId);
    }
}
