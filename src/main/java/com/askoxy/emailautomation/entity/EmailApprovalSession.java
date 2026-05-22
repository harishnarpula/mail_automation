package com.askoxy.emailautomation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;
import java.util.UUID;

/**
 * Persisted state for every email approval workflow.
 *
 * session_type : "CAMPAIGN"     — outbound campaign email waiting for admin approval
 *              : "CLIENT_REPLY" — AI-generated reply to a client email, waiting for admin approval
 *
 * status flow  : PENDING_APPROVAL → APPROVED
 *                                 → REGENERATING → PENDING_APPROVAL (loop)
 *                                               → REGENERATION_FAILED
 *                PENDING_APPROVAL → EXPIRED (after max attempts or timeout)
 */
@Entity
@Table(name = "email_approval_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailApprovalSession {

    // ── Primary Key ───────────────────────────────────────────────────────────

    @Id
    @Column(name = "session_id", length = 100, nullable = false, updatable = false)
    private String sessionId;

    // ── Client Info ───────────────────────────────────────────────────────────

    @Column(name = "client_email", length = 255, nullable = false)
    private String clientEmail;

    @Column(name = "client_name", length = 255)
    private String clientName;

    // ── Session Metadata ──────────────────────────────────────────────────────

    /** CAMPAIGN or CLIENT_REPLY */
    @Column(name = "session_type", length = 50)
    private String sessionType;

    /** QUEUED | PENDING_APPROVAL | APPROVED | REGENERATING | REGENERATION_FAILED | EXPIRED */
    @Column(name = "status", length = 50, nullable = false)
    private String status;

    // ── Email Content ─────────────────────────────────────────────────────────

    @Column(name = "current_subject", length = 500)
    private String currentSubject;

    @Column(name = "current_body", columnDefinition = "TEXT")
    private String currentBody;

    // ── Thread / Message Tracking ─────────────────────────────────────────────

    /**
     * The Gmail Message-ID of the email WE sent to the client.
     * Populated by GmailSendingService after each successful send.
     *
     * Used by GmailPollingService GUARD 4:
     *   client reply's In-Reply-To header MUST match this value
     *   to be accepted as a legitimate reply (prevents processing
     *   standalone emails or replies to unrelated threads).
     */
    @Column(name = "sent_message_id", length = 500)
    private String sentMessageId;

    /**
     * The Gmail thread ID for the conversation with this client.
     * Used to correlate multiple back-and-forth emails in the same thread.
     */
    @Column(name = "email_thread_id", length = 255)
    private String emailThreadId;

    /**
     * The Message-ID of the client's reply that triggered this session.
     * Used for DB-level idempotency — prevents processing the same reply twice.
     */
    @Column(name = "processed_message_id", length = 500)
    private String processedMessageId;

    // ── RAG / File Reference ──────────────────────────────────────────────────

    @Column(name = "file_id", length = 255)
    private String fileId;

    // ── Client Reply Content (for CLIENT_REPLY sessions) ──────────────────────

    @Column(name = "client_reply_content", columnDefinition = "TEXT")
    private String clientReplyContent;

    // ── Feedback Loop ─────────────────────────────────────────────────────────

    @Column(name = "accumulated_feedback", columnDefinition = "TEXT")
    private String accumulatedFeedback;

    @Column(name = "attempt_count")
    @Builder.Default
    private int attemptCount = 0;

    // ── Timestamps ────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at", updatable = false)
    private Date createdAt;

    @UpdateTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "last_updated_at")
    private Date lastUpdatedAt;

    @PrePersist
    private void ensureSessionId() {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
    }
}
