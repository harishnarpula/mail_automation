package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.config.UltraMsgConfig;
import com.askoxy.emailautomation.entity.EmailApprovalSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppNotificationService {

    private final UltraMsgConfig config;
    private final RestTemplate restTemplate;

    // ─── EXISTING: CAMPAIGN approval ─────────────────────────────────────────────

    /**
     * Sends generated CAMPAIGN email to admin for approval.
     */
    public void sendForApproval(EmailApprovalSession session) {
        String message = buildApprovalMessage(session);
        boolean sent = sendWhatsApp(message);
        if (sent) {
            log.info("[WhatsApp] CAMPAIGN approval request sent — sessionId={} attempt={}",
                    session.getSessionId(), session.getAttemptCount());
        } else {
            log.error("[WhatsApp] Failed to send approval request — sessionId={}", session.getSessionId());
        }
    }

    /**
     * Notifies admin after approved email is delivered to client.
     */
    public void sendDeliveryConfirmation(EmailApprovalSession session) {
        String sessionLabel = "CLIENT_REPLY".equals(session.getSessionType()) ? "Reply" : "Campaign";
        String message = "✅ *" + sessionLabel + " Email Delivered Successfully*\n\n"
                + "Client: *" + session.getClientName() + "*\n"
                + "Email: " + session.getClientEmail() + "\n"
                + "Attempt: #" + session.getAttemptCount() + "\n"
                + "Session: " + session.getSessionId() + "\n\n"
                + "The approved email has been sent to the client.";
        sendWhatsApp(message);
        log.info("[WhatsApp] Delivery confirmation sent — sessionId={}", session.getSessionId());
    }

    // ─── NEW: CLIENT_REPLY approval ───────────────────────────────────────────────

    /**
     * Sends generated REPLY email to admin for approval.
     * Shows the client's original message so admin has full context.
     */
    public void sendReplyForApproval(EmailApprovalSession session) {
        String message = buildReplyApprovalMessage(session);
        boolean sent = sendWhatsApp(message);
        if (sent) {
            log.info("[WhatsApp] CLIENT_REPLY approval request sent — sessionId={} attempt={}",
                    session.getSessionId(), session.getAttemptCount());
        } else {
            log.error("[WhatsApp] Failed to send reply approval request — sessionId={}", session.getSessionId());
        }
    }

    /**
     * Notifies admin that a client reply came in but was held
     * because another session is already PENDING_APPROVAL for that client.
     */
    public void sendClientReplyHeldNotification(String clientName, String clientEmail, String replyBody) {
        String message = "⚠️ *Client Reply Received — On Hold*\n\n"
                + "Client: *" + clientName + "*\n"
                + "Email: " + clientEmail + "\n\n"
                + "A reply came in, but there is already an active PENDING_APPROVAL session for this client.\n\n"
                + "📩 *Their message:*\n"
                + truncate(replyBody, 300) + "\n\n"
                + "Please approve or reject the current pending session first.\n"
                + "Their reply will be processed in the next polling cycle.";
        sendWhatsApp(message);
        log.info("[WhatsApp] Client reply held notification sent for client={}", clientEmail);
    }

    // ─── EXISTING: failure alerts ─────────────────────────────────────────────────

    public void sendDeliveryFailureAlert(EmailApprovalSession session, String errorMessage) {
        String msg = "❌ Email delivery failed for " + session.getClientName()
                + ".\nError: " + errorMessage
                + "\n\nThe session is still PENDING. Reply *APPROVE* to retry.";
        sendToAdmin(msg);
    }

    public void sendRegenerationFailureAlert(EmailApprovalSession session, String errorMessage) {
        String msg = "❌ Email regeneration failed for " + session.getClientName()
                + ".\nError: " + errorMessage
                + "\n\nPlease restart the campaign.";
        sendToAdmin(msg);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────────

    private String buildApprovalMessage(EmailApprovalSession session) {
        String feedbackSummary = (session.getAccumulatedFeedback() != null
                && !session.getAccumulatedFeedback().isBlank())
                ? "\n\n📝 *Previous Feedback Applied:*\n" + session.getAccumulatedFeedback()
                : "";

        return "📧 *Email Approval Required* — Attempt #" + session.getAttemptCount() + "\n\n"
                + "👤 *Client:* " + session.getClientName() + "\n"
                + "📬 *To:* " + session.getClientEmail() + "\n"
                + "🔑 *Session:* " + session.getSessionId()
                + feedbackSummary + "\n\n"
                + "━━━━━━━━━━━━━━━━━\n"
                + "📌 *Subject:*\n" + session.getCurrentSubject() + "\n\n"
                + "💬 *Body:*\n" + session.getCurrentBody() + "\n"
                + "━━━━━━━━━━━━━━━━━\n\n"
                + "✅ Reply *APPROVE* to send to client\n"
                + "✏️ Reply with feedback to request changes";
    }

    private String buildReplyApprovalMessage(EmailApprovalSession session) {
        String feedbackSummary = (session.getAccumulatedFeedback() != null
                && !session.getAccumulatedFeedback().isBlank())
                ? "\n\n📝 *Previous Feedback Applied:*\n" + session.getAccumulatedFeedback()
                : "";

        String clientReplyPreview = (session.getClientReplyContent() != null
                && !session.getClientReplyContent().isBlank())
                ? "\n\n💌 *Client's Message:*\n" + truncate(session.getClientReplyContent(), 400)
                : "";

        return "↩️ *Reply Approval Required* — Attempt #" + session.getAttemptCount() + "\n\n"
                + "👤 *Client:* " + session.getClientName() + "\n"
                + "📬 *To:* " + session.getClientEmail() + "\n"
                + "🔑 *Session:* " + session.getSessionId()
                + clientReplyPreview
                + feedbackSummary + "\n\n"
                + "━━━━━━━━━━━━━━━━━\n"
                + "📌 *Our Reply Subject:*\n" + session.getCurrentSubject() + "\n\n"
                + "💬 *Our Reply Body:*\n" + session.getCurrentBody() + "\n"
                + "━━━━━━━━━━━━━━━━━\n\n"
                + "✅ Reply *APPROVE* to send reply to client\n"
                + "✏️ Reply with feedback to request changes";
    }

    private boolean sendWhatsApp(String messageText) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("token", config.getToken());
        body.add("to",    config.getAdminNumber());
        body.add("body",  messageText);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    config.getApiUrl(),
                    request,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("[WhatsApp] UltraMsg response — status={} body={}",
                        response.getStatusCode(), response.getBody());
                return true;
            } else {
                log.error("[WhatsApp] UltraMsg rejected — status={} body={}",
                        response.getStatusCode(), response.getBody());
                return false;
            }

        } catch (Exception e) {
            log.error("[WhatsApp] Exception calling UltraMsg — url={} error={}",
                    config.getApiUrl(), e.getMessage(), e);
            return false;
        }
    }

    private void sendToAdmin(String message) {
        boolean sent = sendWhatsApp(message);
        if (sent) {
            log.info("[WhatsApp] Admin alert sent successfully");
        } else {
            log.error("[WhatsApp] Failed to send admin alert");
        }
    }

    /**
     * Truncates text to maxLength characters with ellipsis if needed.
     * Prevents WhatsApp messages from becoming too long.
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
