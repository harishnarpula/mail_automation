package com.askoxy.emailautomation.repository;

import com.askoxy.emailautomation.entity.EmailApprovalSession;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailApprovalSessionRepository extends JpaRepository<EmailApprovalSession, String> {

    // ── Idempotency checks ────────────────────────────────────────────────────

    boolean existsByProcessedMessageId(String processedMessageId);

    // ── NEW: Check if In-Reply-To matches an email WE sent ───────────────────
    // Used by GmailPollingService to validate client replies are actually
    // replying to emails we sent, not some other thread.
    boolean existsBySentMessageId(String sentMessageId);

    // ── NEW: Count sessions that have sentMessageId populated ────────────────
    // Used by GmailPollingService to detect whether strict mode is active.
    // Returns 0 on first deploy (before sentMessageId tracking was added),
    // in which case the poller falls back to time-based session check.
    long countBySentMessageIdNotNull();

    // ── NULL-SAFE session lookup ──────────────────────────────────────────────
    // Handles rows where session_type was NULL (legacy rows before the fix).
    // Matches rows where session_type = :sessionType OR session_type IS NULL.
    @Query("SELECT s FROM EmailApprovalSession s " +
            "WHERE s.clientEmail = :clientEmail " +
            "AND s.status = :status " +
            "AND (s.sessionType = :sessionType OR s.sessionType IS NULL) " +
            "ORDER BY s.lastUpdatedAt DESC")
    List<EmailApprovalSession> findByClientEmailAndStatusAndSessionTypeOrNullOrderByLastUpdatedAtDesc(
            @Param("clientEmail") String clientEmail,
            @Param("status")      String status,
            @Param("sessionType") String sessionType,
            Pageable pageable);

    default Optional<EmailApprovalSession> findTopByClientEmailAndStatusAndSessionTypeOrNullOrderByLastUpdatedAtDesc(
            String clientEmail,
            String status,
            String sessionType
    ) {
        return findByClientEmailAndStatusAndSessionTypeOrNullOrderByLastUpdatedAtDesc(
                clientEmail, status, sessionType, PageRequest.of(0, 1)
        ).stream().findFirst();
    }

    // ── Active session guard ──────────────────────────────────────────────────
    // Used to prevent duplicate CLIENT_REPLY sessions when client sends multiple
    // emails before admin approves the first one.
    @Query("SELECT COUNT(s) > 0 FROM EmailApprovalSession s " +
            "WHERE s.clientEmail = :clientEmail " +
            "AND s.sessionType = 'CLIENT_REPLY' " +
            "AND s.status IN ('PENDING_APPROVAL', 'REGENERATING')")
    boolean hasActivePendingClientReplySession(@Param("clientEmail") String clientEmail);

    List<EmailApprovalSession> findAllByClientEmailAndStatusIn(String clientEmail, List<String> pendingApproval);

    Optional<EmailApprovalSession> findTopByStatusOrderByCreatedAtDesc(String pendingApproval);

    Optional<EmailApprovalSession> findTopByClientEmailAndStatusInOrderByCreatedAtDesc(String clientEmail, List<String> pendingApproval);

    Optional<EmailApprovalSession> findTopByStatusAndSessionTypeOrderByCreatedAtAsc(String status, String sessionType);

    List<EmailApprovalSession> findTop20ByStatusAndSessionTypeOrderByCreatedAtAsc(String status, String sessionType);
}
