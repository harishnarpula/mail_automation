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

        log.info("[ClientReplyProcessor] Received reply — clientEmail={} subject={} messageId={}",
                clientEmail, subject, messageId);

        if (messageId != null && sessionRepository.existsByProcessedMessageId(messageId)) {
            log.warn("[ClientReplyProcessor] messageId={} already processed. Skipping.", messageId);
            return;
        }

        EmailApprovalSession lastApprovedSession = findLastApprovedSession(clientEmail);
        if (lastApprovedSession == null) {
            log.warn("[ClientReplyProcessor] No approved session for clientEmail={}. Skipping.", clientEmail);
            return;
        }

        log.info("[ClientReplyProcessor] Found approved context sessionId={} type={}",
                lastApprovedSession.getSessionId(), lastApprovedSession.getSessionType());

        boolean hasActivePendingSession = sessionRepository
                .findTopByClientEmailAndStatusInOrderByCreatedAtDesc(
                        clientEmail, List.of("PENDING_APPROVAL", "REGENERATING"))
                .isPresent();

        if (hasActivePendingSession) {
            log.info("[ClientReplyProcessor] Active pending session exists — queueing reply for clientEmail={}", clientEmail);
            queueClientReply(lastApprovedSession, subject, replyBody, messageId, inReplyTo);
            return;
        }

        // Build session with ALL required fields set BEFORE generation
        // CRITICAL: clientReplyContent must be set here — RegenerationService reads it on every feedback round
        EmailApprovalSession immediateSession = new EmailApprovalSession();
        immediateSession.setClientName(lastApprovedSession.getClientName());
        immediateSession.setClientEmail(clientEmail);
        immediateSession.setFileId(lastApprovedSession.getFileId());
        immediateSession.setSessionType("CLIENT_REPLY");
        immediateSession.setCurrentSubject(subject);
        immediateSession.setClientReplyContent(replyBody);   // ← MUST be set; used on every regen round
        immediateSession.setProcessedMessageId(messageId);
        immediateSession.setEmailThreadId(inReplyTo != null ? inReplyTo : messageId);
        immediateSession.setStatus("REGENERATING");

        log.info("[ClientReplyProcessor] Creating immediate CLIENT_REPLY session — clientName='{}' replyLength={}",
                lastApprovedSession.getClientName(), replyBody != null ? replyBody.length() : 0);

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
            log.warn("[ClientReplyProcessor] No approved context for queued session={} client={}. Expiring.",
                    queued.getSessionId(), queued.getClientEmail());
            queued.setStatus("EXPIRED");
            sessionRepository.save(queued);
            return;
        }

        log.info("[ClientReplyProcessor] Draining queued session={} for client={}",
                queued.getSessionId(), queued.getClientEmail());

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
        queued.setClientReplyContent(replyBody);             // ← preserved for when queue drains
        queued.setProcessedMessageId(messageId);
        queued.setEmailThreadId(inReplyTo != null ? inReplyTo : messageId);
        queued.setStatus("QUEUED");

        EmailApprovalSession saved = sessionRepository.save(queued);
        log.info("[ClientReplyProcessor] Queued CLIENT_REPLY session={} client={} messageId={}",
                saved.getSessionId(), saved.getClientEmail(), messageId);
    }

    private void generateReplyAndMoveToPendingApproval(EmailApprovalSession targetSession,
                                                       EmailApprovalSession lastApprovedSession,
                                                       String feedbackHistory) {
        String clientName        = lastApprovedSession.getClientName();
        String fileId            = lastApprovedSession.getFileId();
        String clientReplyContent = targetSession.getClientReplyContent() != null
                ? targetSession.getClientReplyContent()
                : "(empty client reply)";

        log.info("[ClientReplyProcessor] generateReply — clientName='{}' feedbackHistory='{}' replyLength={}",
                clientName, feedbackHistory, clientReplyContent.length());

        String vectorStoreId = resolveVectorStoreId(fileId, lastApprovedSession);
        String companyContext = retrievalService.retrieve(
                "company overview products services capabilities what we build what we offer",
                vectorStoreId, 10);

        String originalSubject = lastApprovedSession.getCurrentSubject() != null
                ? lastApprovedSession.getCurrentSubject()
                : (targetSession.getCurrentSubject() != null
                   ? targetSession.getCurrentSubject()
                   : "Re: Conversation");

        GeneratedEmailDto rawReply = replyGenerationAgent.generateReply(
                clientName,
                clientReplyContent,
                originalSubject,
                companyContext,
                feedbackHistory
        );

        log.info("[ClientReplyProcessor] ReplyGenerationAgent done — subject='{}'", rawReply.getSubject());

        GeneratedEmailDto finalReply = complianceAgent.reviewAndRefine(rawReply, feedbackHistory, true);

        // CRITICAL: preserve clientReplyContent on the saved session
        // RegenerationService.regenerateReply() reads this field on EVERY feedback round
        targetSession.setClientName(clientName);
        targetSession.setClientEmail(lastApprovedSession.getClientEmail());
        targetSession.setCurrentSubject(finalReply.getSubject());
        targetSession.setCurrentBody(finalReply.getBody());
        targetSession.setFileId(fileId);
        targetSession.setSessionType("CLIENT_REPLY");
        targetSession.setClientReplyContent(clientReplyContent); // ← re-set explicitly to survive regen rounds
        targetSession.setStatus("PENDING_APPROVAL");

        EmailApprovalSession savedSession = sessionRepository.save(targetSession);
        log.info("[ClientReplyProcessor] Saved PENDING_APPROVAL session={} client={}",
                savedSession.getSessionId(), savedSession.getClientEmail());

        whatsAppNotificationService.sendReplyForApproval(savedSession);
    }

    /**
     * Called by RegenerationService for CLIENT_REPLY sessions.
     * NOT used in the feedback loop — RegenerationService.regenerateReply() handles that directly.
     * Kept for any external callers.
     */
    public GeneratedEmailDto regenerateReply(EmailApprovalSession session, String vectorStoreId) {
        String feedback = session.getAccumulatedFeedback() != null
                ? session.getAccumulatedFeedback().trim() : "";

        log.info("[ClientReplyProcessor] regenerateReply called — sessionId={} feedback='{}'",
                session.getSessionId(), feedback);

        String companyContext = retrievalService.retrieve(
                "company overview products services capabilities what we build what we offer",
                vectorStoreId, 10);

        GeneratedEmailDto rawReply = replyGenerationAgent.generateReply(
                session.getClientName(),
                session.getClientReplyContent(),
                session.getCurrentSubject(),
                companyContext,
                feedback
        );

        return complianceAgent.reviewAndRefine(rawReply, feedback, true);
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