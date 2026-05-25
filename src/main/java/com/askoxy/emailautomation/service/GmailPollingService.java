package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.entity.EmailApprovalSession;
import com.askoxy.emailautomation.repository.EmailApprovalSessionRepository;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Date;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailPollingService {

    private final ClientReplyProcessorService clientReplyProcessorService;
    private final EmailApprovalSessionRepository sessionRepository;

    @Value("${app.gmail.imap.host:imap.gmail.com}")
    private String imapHost;

    @Value("${app.gmail.imap.port:993}")
    private int imapPort;

    @Value("${app.gmail.imap.password}")
    private String imapPassword;

    @Value("${spring.mail.username}")
    private String ourEmailAddress;

    @Value("${app.gmail.poll-interval-ms:60000}")
    private long pollIntervalMs;

    @Value("${app.gmail.lookback-hours:24}")
    private int lookbackHours;

    @Value("${app.gmail.campaign-validity-days:7}")
    private int campaignValidityDays;

    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${app.gmail.poll-interval-ms:60000}")
    public void pollInbox() {
        log.info("[GmailPoller] Polling inbox for account={} lookbackHours={} intervalMs={}",
                ourEmailAddress, lookbackHours, pollIntervalMs);
        Store store = null;
        Folder inbox = null;

        try {
            store = connectToGmail();
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            Date cutoff = new Date(System.currentTimeMillis() - (lookbackHours * 60L * 60L * 1000L));
            SearchTerm unseenTerm = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
            SearchTerm dateTerm   = new ReceivedDateTerm(jakarta.mail.search.ComparisonTerm.GE, cutoff);
            SearchTerm combined   = new AndTerm(unseenTerm, dateTerm);
            Message[] messages    = inbox.search(combined);

            if (messages == null || messages.length == 0) {
                log.info("[GmailPoller] No unread messages in last {} hours.", lookbackHours);
                return;
            }

            log.info("[GmailPoller] Found {} unread message(s) in last {} hours. Processing...",
                    messages.length, lookbackHours);

            for (Message message : messages) {
                try {
                    processMessage(message);
                } catch (Exception e) {
                    log.error("[GmailPoller] Error processing message — skipping. subject={}",
                            safeSubject(message), e);
                }
            }

        } catch (Exception e) {
            log.error("[GmailPoller] Failed to connect or poll Gmail IMAP", e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    private void processMessage(Message message) throws MessagingException, IOException {

        String messageId = normalizeMessageId(getHeader(message, "Message-ID"));
        String inReplyTo = normalizeMessageId(getHeader(message, "In-Reply-To"));
        String references = getHeader(message, "References");
        String subject   = message.getSubject() != null ? message.getSubject().trim() : "(no subject)";

        log.debug("[GmailPoller] Processing — messageId={} inReplyTo={} subject={}", messageId, inReplyTo, subject);

        // ── GUARD 1: Must be an actual reply ─────────────────────────────────
        if (inReplyTo == null || inReplyTo.isBlank()) {
            log.debug("[GmailPoller] Skipping non-reply (no In-Reply-To). subject={}", subject);
            markAsSeen(message);
            return;
        }

        // ── GUARD 2: Extract sender ───────────────────────────────────────────
        String senderEmail = extractSenderEmail(message);
        if (senderEmail == null) {
            log.warn("[GmailPoller] Could not extract sender. Skipping. subject={}", subject);
            markAsSeen(message);
            return;
        }

        // ── GUARD 3: Skip self-sent ───────────────────────────────────────────
        if (senderEmail.equalsIgnoreCase(ourEmailAddress)) {
            log.debug("[GmailPoller] Skipping self-sent from={}.", senderEmail);
            markAsSeen(message);
            return;
        }

        // ── GUARD 4: Validate reply belongs to a known client session ─────────
        //
        // STRATEGY (in order):
        //   A) Direct match: inReplyTo == sentMessageId in DB  (ideal, new sessions)
        //   B) Email fallback: sender has a recent APPROVED session (handles @LAPTOP bug
        //      sessions and cases where Gmail rewrites Message-IDs)
        //
        // We do NOT hard-reject based on sentMessageId alone because:
        //   - Old sessions had @LAPTOP fake IDs that don't match Gmail's real Message-ID
        //   - Gmail sometimes rewrites Message-IDs for externally delivered mail
        //
        boolean sentMessageIdTrackingActive = sessionRepository.countBySentMessageIdNotNull() > 0;
        boolean validatedBySentMessageId    = false;
        boolean validatedByEmailFallback    = false;

        if (sentMessageIdTrackingActive) {
            validatedBySentMessageId = sessionRepository.existsBySentMessageId(inReplyTo);
            log.debug("[GmailPoller] sentMessageId lookup — inReplyTo={} matched={}", inReplyTo, validatedBySentMessageId);
        }

        if (!validatedBySentMessageId) {
            // Fallback: does sender have ANY recent APPROVED session (CAMPAIGN or CLIENT_REPLY)?
            Date validityCutoff = new Date(System.currentTimeMillis() - (campaignValidityDays * 24L * 60L * 60L * 1000L));

            Optional<EmailApprovalSession> recentSession = sessionRepository
                    .findTopByClientEmailAndStatusAndSessionTypeOrNullOrderByLastUpdatedAtDesc(
                            senderEmail, "APPROVED", "CAMPAIGN")
                    .or(() -> sessionRepository
                            .findTopByClientEmailAndStatusAndSessionTypeOrNullOrderByLastUpdatedAtDesc(
                                    senderEmail, "APPROVED", "CLIENT_REPLY"));

            if (recentSession.isPresent()) {
                EmailApprovalSession s = recentSession.get();
                boolean withinValidity = s.getLastUpdatedAt() != null
                        && s.getLastUpdatedAt().after(validityCutoff);

                if (withinValidity) {
                    validatedByEmailFallback = true;
                    log.info("[GmailPoller] ✅ Fallback validation passed — sender={} matched sessionId={} type={} sentMessageId='{}'",
                            senderEmail, s.getSessionId(), s.getSessionType(), s.getSentMessageId());
                    log.info("[GmailPoller] ℹ️  Note: inReplyTo='{}' did not match sentMessageId='{}' — " +
                                    "likely @LAPTOP bug session or Gmail Message-ID rewrite. Proceeding via email fallback.",
                            inReplyTo, s.getSentMessageId());
                } else {
                    log.info("[GmailPoller] ⚠️ Fallback failed — sender={} has APPROVED session but it's older than {} days. Skipping.",
                            senderEmail, campaignValidityDays);
                }
            } else {
                log.info("[GmailPoller] ⚠️ Fallback failed — sender={} has no recent APPROVED session. Not a known client. Skipping.",
                        senderEmail);
            }
        } else {
            log.info("[GmailPoller] ✅ Direct sentMessageId match — inReplyTo={} sender={}", inReplyTo, senderEmail);
        }

        if (!validatedBySentMessageId && !validatedByEmailFallback) {
            markAsSeen(message);
            return;
        }

        // ── GUARD 5: In-memory idempotency ────────────────────────────────────
        String dedupKey = messageId != null ? messageId : (senderEmail + "|" + subject);
        if (processedMessageIds.contains(dedupKey)) {
            log.debug("[GmailPoller] Already processed (memory). messageId={}", messageId);
            markAsSeen(message);
            return;
        }

        // ── GUARD 6: DB idempotency ───────────────────────────────────────────
        if (messageId != null && sessionRepository.existsByProcessedMessageId(messageId)) {
            log.debug("[GmailPoller] Already processed (DB). messageId={}", messageId);
            processedMessageIds.add(dedupKey);
            markAsSeen(message);
            return;
        }

        // ── All guards passed ─────────────────────────────────────────────────
        String replyBody    = extractTextBody(message);
        String strippedBody = stripQuotedText(replyBody);
        if (strippedBody == null || strippedBody.isBlank()) {
            log.warn("[GmailPoller] Empty body after stripping. Using full body. sender={}", senderEmail);
            strippedBody = replyBody != null ? replyBody : "(empty reply)";
        }

        log.info("[GmailPoller] ✅ Client reply accepted — sender={} subject={} messageId={} validatedBy={}",
                senderEmail, subject, messageId,
                validatedBySentMessageId ? "sentMessageId" : "emailFallback");

        clientReplyProcessorService.processClientReply(
                senderEmail, subject, strippedBody, messageId, inReplyTo, references
        );

        processedMessageIds.add(dedupKey);
        markAsSeen(message);
    }

    // ── IMAP Connection ───────────────────────────────────────────────────────

    private Store connectToGmail() throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", imapHost);
        props.put("mail.imaps.port", String.valueOf(imapPort));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.ssl.trust", imapHost);
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "10000");

        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        store.connect(imapHost, ourEmailAddress, imapPassword);

        log.debug("[GmailPoller] Connected to Gmail IMAP as={}", ourEmailAddress);
        return store;
    }

    // ── Utility Helpers ───────────────────────────────────────────────────────

    private String extractTextBody(Message message) throws MessagingException, IOException {
        return extractTextFromPart(message);
    }

    private String extractTextFromPart(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            MimeMultipart multipart = (MimeMultipart) part.getContent();
            String htmlFallback = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (bodyPart.isMimeType("text/plain")) {
                    String text = (String) bodyPart.getContent();
                    if (text != null && !text.isBlank()) return text;
                }
                if (bodyPart.isMimeType("text/html") && htmlFallback == null) {
                    htmlFallback = stripHtmlTags((String) bodyPart.getContent());
                }
                if (bodyPart.isMimeType("multipart/*")) {
                    String nested = extractTextFromPart(bodyPart);
                    if (nested != null && !nested.isBlank()) return nested;
                }
            }
            return htmlFallback;
        }
        return null;
    }

    private String stripQuotedText(String body) {
        if (body == null) return null;
        String[] lines = body.split("\\r?\\n");
        StringBuilder stripped = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("On .+wrote:.*")) break;
            if (trimmed.startsWith("-----Original Message-----")) break;
            if (trimmed.startsWith("________________________________")) break;
            if (trimmed.equals("--")) break;
            if (trimmed.startsWith(">")) continue;
            stripped.append(line).append("\n");
        }
        return stripped.toString().trim();
    }

    private String extractSenderEmail(Message message) {
        try {
            Address[] from = message.getFrom();
            if (from != null && from.length > 0) {
                if (from[0] instanceof InternetAddress ia) {
                    return ia.getAddress().toLowerCase().trim();
                }
                return from[0].toString().toLowerCase().trim();
            }
        } catch (MessagingException e) {
            log.warn("[GmailPoller] Could not extract sender", e);
        }
        return null;
    }

    private String getHeader(Message message, String headerName) {
        try {
            String[] headers = message.getHeader(headerName);
            if (headers != null && headers.length > 0) return headers[0].trim();
        } catch (MessagingException e) {
            log.debug("[GmailPoller] Could not read header={}", headerName);
        }
        return null;
    }

    /**
     * Normalizes a Message-ID by preserving the full <...> bracket form.
     * Strips surrounding whitespace only.
     * Does NOT strip brackets — comparison in DB uses the bracketed form.
     */
    private String normalizeMessageId(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        int start = trimmed.indexOf('<');
        int end   = trimmed.indexOf('>');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1).trim();
        }
        return trimmed;
    }

    private void markAsSeen(Message message) {
        try { message.setFlag(Flags.Flag.SEEN, true); }
        catch (MessagingException e) { log.warn("[GmailPoller] Could not mark SEEN", e); }
    }

    private String stripHtmlTags(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String safeSubject(Message message) {
        try { return message.getSubject(); } catch (Exception e) { return "(unknown)"; }
    }

    private void closeQuietly(Folder folder, Store store) {
        try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception ignored) {}
        try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) {}
    }
}
