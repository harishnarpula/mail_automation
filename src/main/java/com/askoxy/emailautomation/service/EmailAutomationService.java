package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.agent.*;
import com.askoxy.emailautomation.dto.EmailAutomationResponse;
import com.askoxy.emailautomation.dto.GeneratedEmailDto;
import com.askoxy.emailautomation.entity.EmailApprovalSession;
import com.askoxy.emailautomation.entity.UploadedFile;
import com.askoxy.emailautomation.repository.EmailApprovalSessionRepository;
import com.askoxy.emailautomation.repository.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailAutomationService {

    private final RetrievalService retrievalService;
    private final UploadedFileRepository uploadedFileRepository;
    private final EmailApprovalSessionRepository sessionRepository;
    private final ApprovalOrchestrationService approvalOrchestrationService;

    private final ClientIntelligenceAgent clientIntelligenceAgent;
    private final OpportunityMatchingAgent opportunityMatchingAgent;
    private final StrategyAgent strategyAgent;
    private final EmailGenerationAgent emailGenerationAgent;
    private final ComplianceAgent complianceAgent;

    @Transactional
    public EmailAutomationResponse startCampaign(String clientName, String clientEmail) {

        // 1. Auto-resolve the most recently uploaded COMPLETED file
        UploadedFile uploadedFile = uploadedFileRepository
                .findTopByUploadStatusOrderByCreatedAtDesc("COMPLETED")
                .orElseThrow(() -> new RuntimeException(
                        "No completed file found. Please upload a PDF first."));

        log.info("[Campaign] Using fileId={} fileName={} for client={}",
                uploadedFile.getFileId(), uploadedFile.getFileName(), clientEmail);

        // 2. Expire any stale sessions for this client
        List<EmailApprovalSession> staleSessions = sessionRepository
                .findAllByClientEmailAndStatusIn(
                        clientEmail, List.of("PENDING_APPROVAL", "REGENERATING", "QUEUED"));
        if (!staleSessions.isEmpty()) {
            staleSessions.forEach(s -> {
                s.setStatus("EXPIRED");
                log.warn("[Campaign] Expiring stale session={} for client={}",
                        s.getSessionId(), clientEmail);
            });
            sessionRepository.saveAll(staleSessions);
        }

        // 3. RAG retrieval
        String context = retrievalService.retrieve(
                "company overview business model offerings products services",
                uploadedFile.getVectorStoreId(), 10);

        // 4. Agent pipeline
        String intelligence = clientIntelligenceAgent.analyze(clientName, context);
        String opportunity  = opportunityMatchingAgent.findOpportunity(clientName, intelligence);
        String strategy     = strategyAgent.buildStrategy(clientName, opportunity);

        GeneratedEmailDto rawEmail = emailGenerationAgent.generateEmail(
                clientName, intelligence, strategy, context);

        GeneratedEmailDto finalEmail = complianceAgent.reviewAndRefine(rawEmail);

        // 5. Initiate approval session
        approvalOrchestrationService.initiateApprovalSession(
                finalEmail, clientName, clientEmail, uploadedFile.getFileId());

        log.info("[Campaign] Pending admin approval for clientEmail={}", clientEmail);

        return EmailAutomationResponse.builder()
                .success(true)
                .message("Email generated and sent to admin for WhatsApp approval")
                .clientEmail(clientEmail)
                .generatedEmail(finalEmail)
                .build();
    }
}
