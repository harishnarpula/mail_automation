package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.dto.GeneratedEmailDto;
import com.askoxy.emailautomation.entity.EmailApprovalSession;
import com.askoxy.emailautomation.repository.EmailApprovalSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${spring.ai.openai.api-key}")
    private String openAiKey;

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
        log.info("[Rejection] START — sessionId={} sessionType={} attempt={} feedback='{}'",
                session.getSessionId(), session.getSessionType(), session.getAttemptCount(), feedback);

        log.info("[Rejection] Current subject='{}'", session.getCurrentSubject());
        log.info("[Rejection] Current body (first 200 chars)='{}'",
                session.getCurrentBody() != null
                        ? session.getCurrentBody().substring(0, Math.min(200, session.getCurrentBody().length()))
                        : "NULL");
        log.info("[Rejection] Accumulated feedback so far='{}'", session.getAccumulatedFeedback());

        session.setAccumulatedFeedback(
                accumulateFeedback(session.getAccumulatedFeedback(), session.getAttemptCount(), feedback));
        session.setStatus("REGENERATING");
        session.setAttemptCount(session.getAttemptCount() + 1);
        sessionRepository.save(session);

        log.info("[Rejection] Session saved as REGENERATING — now calling RegenerationService.regenerate()");

        try {

            log.info("[Regeneration] ENTRY — sessionId={} attemptCount={} fileId='{}'",
                    session.getSessionId(), session.getAttemptCount(), session.getFileId());
            log.info("[Regeneration] OpenAI key resolved (first 7 chars)='{}'",
                    openAiKey != null && openAiKey.length() > 7 ? openAiKey.substring(0, 7) : "TOO_SHORT_OR_NULL");
            log.info("[Regeneration] Accumulated feedback='{}'", session.getAccumulatedFeedback());
            GeneratedEmailDto revised = regenerationService.regenerate(session);

            log.info("[Rejection] Regeneration SUCCESS — new subject='{}'", revised.getSubject());

            session.setCurrentSubject(revised.getSubject());
            session.setCurrentBody(revised.getBody());
            session.setStatus("PENDING_APPROVAL");
            sessionRepository.save(session);

            if ("CLIENT_REPLY".equals(session.getSessionType())) {
                whatsAppNotificationService.sendReplyForApproval(session);
            } else {
                whatsAppNotificationService.sendForApproval(session);
            }

        } catch (Exception e) {
            log.error("[Rejection] Regeneration FAILED — sessionId={} exceptionClass={} message='{}'",
                    session.getSessionId(), e.getClass().getName(), e.getMessage());
            log.error("[Rejection] Full stack trace:", e);

            // Dig into root cause — Spring AI wraps OpenAI errors
            Throwable cause = e.getCause();
            while (cause != null) {
                log.error("[Rejection] Caused by: exceptionClass={} message='{}'",
                        cause.getClass().getName(), cause.getMessage());
                cause = cause.getCause();
            }

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
     * Case-insensitive APPROVE detection.
     *
     * Accepts all variants:
     *   "approve", "Approve", "APPROVE", "approve.", "approve ✅",
     *   "please approve", "ok approve it", "APPROVE NOW", etc.
     *
     * Rejects feedback that merely contains the word in context:
     *   "don't approve" → still treated as feedback (no word boundary stripping issue
     *   since the regex uses \bAPPROVE\b on the uppercased string)
     */
    private boolean isApproveCommand(String normalizedReply) {
        if (normalizedReply == null || normalizedReply.isBlank()) return false;

        // Uppercase everything first — makes all checks truly case-insensitive
        String upper = normalizedReply.toUpperCase();

        // Fast path: exact match after uppercasing
        if (APPROVE_KEYWORD.equals(upper)) return true;

        // Strip all non-alphanumeric characters (punctuation, emojis, spaces normalised)
        // then check for word-boundary match of APPROVE
        String alphaOnly = upper.replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return alphaOnly.matches(".*\\bAPPROVE\\b.*");
    }
}