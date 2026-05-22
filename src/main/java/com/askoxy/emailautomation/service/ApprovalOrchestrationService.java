package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.dto.GeneratedEmailDto;
import com.askoxy.emailautomation.entity.EmailApprovalSession;
import com.askoxy.emailautomation.repository.EmailApprovalSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalOrchestrationService {

    private static final String APPROVE_KEYWORD = "APPROVE";

    private final EmailApprovalSessionRepository sessionRepository;
    private final RegenerationService regenerationService;
    private final EmailDeliveryService emailDeliveryService;
    private final WhatsAppNotificationService whatsAppNotificationService;

    /**
     * Called by WhatsAppWebhookController when admin sends any WhatsApp reply.
     *
     * Handles both CAMPAIGN and CLIENT_REPLY sessions transparently.
     * The most recent PENDING_APPROVAL session is always the active one.
     */
    @Transactional
    public void processAdminReply(String replyText) {
        String normalizedReply = normalizeReply(replyText);

        EmailApprovalSession session = sessionRepository
                .findTopByStatusOrderByCreatedAtDesc("PENDING_APPROVAL")
                .orElse(null);

        if (session == null) {
            log.warn("[Approval] Admin replied '{}' but NO PENDING_APPROVAL session exists. Ignoring.",
                    normalizedReply);
            return;
        }

        // Race condition guard — webhook + polling both firing
        if (isTerminalStatus(session.getStatus())) {
            log.warn("[Approval] Session {} is already in terminal state={}. Ignoring duplicate reply.",
                    session.getSessionId(), session.getStatus());
            return;
        }

        log.info("[Approval] Processing admin reply for sessionId={} sessionType={} status={}",
                session.getSessionId(), session.getSessionType(), session.getStatus());

        log.info("[Approval] Admin rawReply='{}' normalizedReply='{}'", replyText, normalizedReply);

        if (isApproveCommand(normalizedReply)) {
            handleApproval(session);
        } else {
            handleRejection(session, normalizedReply);
        }
    }

    private void handleApproval(EmailApprovalSession session) {
        log.info("[Approval] APPROVED — sessionId={} sessionType={} attempt={}",
                session.getSessionId(), session.getSessionType(), session.getAttemptCount());

        // Move to terminal state FIRST — prevents double-send from race conditions
        session.setStatus("APPROVED");
        sessionRepository.save(session);

        GeneratedEmailDto emailDto = GeneratedEmailDto.builder()
                .subject(session.getCurrentSubject())
                .body(session.getCurrentBody())
                .build();

        try {
            String inReplyTo = null;
            String references = null;
            if ("CLIENT_REPLY".equals(session.getSessionType())) {
                inReplyTo = session.getEmailThreadId();
                references = session.getEmailThreadId();
            }

            String sentMessageId = emailDeliveryService.send(
                    session.getClientEmail(), emailDto, inReplyTo, references);
            session.setSentMessageId(sentMessageId);
            if (session.getEmailThreadId() == null || session.getEmailThreadId().isBlank()) {
                session.setEmailThreadId(sentMessageId);
            }
            sessionRepository.save(session);

            whatsAppNotificationService.sendDeliveryConfirmation(session);

            log.info("[Approval] Email delivered to client={} sessionType={}",
                    session.getClientEmail(), session.getSessionType());

        } catch (Exception e) {
            log.error("[Approval] Email delivery failed for session={}, reverting to PENDING_APPROVAL",
                    session.getSessionId(), e);
            session.setStatus("PENDING_APPROVAL");
            sessionRepository.save(session);
            whatsAppNotificationService.sendDeliveryFailureAlert(session, e.getMessage());
        }
    }

    private void handleRejection(EmailApprovalSession session, String feedback) {
        log.info("[Approval] REJECTED — sessionId={} sessionType={} attempt={} feedback={}",
                session.getSessionId(), session.getSessionType(), session.getAttemptCount(), feedback);

        session.setAccumulatedFeedback(
                accumulateFeedback(session.getAccumulatedFeedback(), session.getAttemptCount(), feedback));
        session.setStatus("REGENERATING");
        session.setAttemptCount(session.getAttemptCount() + 1);
        sessionRepository.save(session);

        try {
            GeneratedEmailDto revised = regenerationService.regenerate(session);
            session.setCurrentSubject(revised.getSubject());
            session.setCurrentBody(revised.getBody());
            session.setStatus("PENDING_APPROVAL");
            sessionRepository.save(session);

            // Use correct WhatsApp notification based on session type
            if ("CLIENT_REPLY".equals(session.getSessionType())) {
                whatsAppNotificationService.sendReplyForApproval(session);
            } else {
                whatsAppNotificationService.sendForApproval(session);
            }

        } catch (Exception e) {
            log.error("[Approval] Regeneration failed for session={}", session.getSessionId(), e);
            session.setStatus("REGENERATION_FAILED");
            sessionRepository.save(session);
            whatsAppNotificationService.sendRegenerationFailureAlert(session, e.getMessage());
        }
    }

    /**
     * Called by EmailAutomationService to start a new CAMPAIGN approval session.
     */
    @Transactional
    public EmailApprovalSession initiateApprovalSession(GeneratedEmailDto email,
                                                        String clientName,
                                                        String clientEmail,
                                                        String fileId) {
        EmailApprovalSession session = new EmailApprovalSession();
        session.setClientName(clientName);
        session.setClientEmail(clientEmail);
        session.setCurrentSubject(email.getSubject());
        session.setCurrentBody(email.getBody());
        session.setFileId(fileId);
        session.setSessionType("CAMPAIGN");
        session.setStatus("PENDING_APPROVAL");

        EmailApprovalSession saved = sessionRepository.save(session);
        whatsAppNotificationService.sendForApproval(saved);

        log.info("[Approval] CAMPAIGN session created — sessionId={} client={}",
                saved.getSessionId(), clientEmail);
        return saved;
    }

    private boolean isTerminalStatus(String status) {
        return "APPROVED".equals(status)
                || "EXPIRED".equals(status)
                || "REGENERATION_FAILED".equals(status);
    }

    private String accumulateFeedback(String existing, int attemptNumber, String newFeedback) {
        String entry = "[Round " + attemptNumber + "]: " + newFeedback;
        return (existing == null || existing.isBlank()) ? entry : existing + "\n" + entry;
    }

    private String normalizeReply(String replyText) {
        return replyText == null ? "" : replyText.trim();
    }

    /**
     * Accept realistic WhatsApp approval variants:
     * APPROVE, approve, approve., APPROVE ✅, please approve, etc.
     */
    private boolean isApproveCommand(String normalizedReply) {
        if (normalizedReply == null || normalizedReply.isBlank()) return false;

        String upper = normalizedReply.toUpperCase();
        if (APPROVE_KEYWORD.equals(upper)) return true;

        String alphaNum = upper.replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return alphaNum.matches(".*\\bAPPROVE\\b.*");
    }
}
