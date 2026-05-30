package com.aiautomationservice.service;

import com.aiautomationservice.agent.ClientIntelligenceAgent;
import com.aiautomationservice.agent.ComplianceAgent;
import com.aiautomationservice.agent.EmailGenerationAgent;
import com.aiautomationservice.agent.OpportunityMatchingAgent;
import com.aiautomationservice.agent.StrategyAgent;
import com.aiautomationservice.dto.EmailAutomationDto;
import com.aiautomationservice.dto.GeneratedEmailDto;
import com.aiautomationservice.entity.CampaignClient;
import com.aiautomationservice.entity.EmailApprovalSession;
import com.aiautomationservice.entity.UploadedFile;
import com.aiautomationservice.repository.CampaignClientRepository;
import com.aiautomationservice.repository.EmailApprovalSessionRepository;
import com.aiautomationservice.repository.UploadedFileRepository;
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
    private final CampaignClientRepository campaignClientRepository;
    private final ApprovalOrchestrationService approvalOrchestrationService;

    private final ClientIntelligenceAgent clientIntelligenceAgent;
    private final OpportunityMatchingAgent opportunityMatchingAgent;
    private final StrategyAgent strategyAgent;
    private final EmailGenerationAgent emailGenerationAgent;
    private final ComplianceAgent complianceAgent;

    @Transactional
    public EmailAutomationDto startCampaign(String clientName, String clientEmail) {
        UploadedFile uploadedFile = uploadedFileRepository
                .findTopByUploadStatusOrderByCreatedAtDesc("COMPLETED")
                .orElseThrow(() -> new RuntimeException(
                        "No completed file found. Please upload a PDF first."));

        log.info("[Campaign] Using fileId={} fileName={} for client={}",
                uploadedFile.getFileId(), uploadedFile.getFileName(), clientEmail);

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

        String context = retrievalService.retrieve(
                "company overview business model offerings products services",
                uploadedFile.getVectorStoreId(), 10);

        String intelligence = clientIntelligenceAgent.analyze(clientName, context);
        String opportunity = opportunityMatchingAgent.findOpportunity(clientName, intelligence);
        String strategy = strategyAgent.buildStrategy(clientName, opportunity);

        GeneratedEmailDto rawEmail = emailGenerationAgent.generateEmail(
                clientName, intelligence, strategy, context);

        GeneratedEmailDto finalEmail = complianceAgent.reviewAndRefine(rawEmail);

        approvalOrchestrationService.initiateApprovalSession(
                finalEmail, clientName, clientEmail, uploadedFile.getFileId());

        log.info("[Campaign] Pending admin approval for clientEmail={}", clientEmail);

        return EmailAutomationDto.builder()
                .success(true)
                .message("Email generated and sent to admin for WhatsApp approval")
                .clientEmail(clientEmail)
                .generatedEmail(finalEmail)
                .build();
    }

}
