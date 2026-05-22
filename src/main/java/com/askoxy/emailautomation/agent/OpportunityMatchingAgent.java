package com.askoxy.emailautomation.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpportunityMatchingAgent {

    private final ChatClient chatClient;

    public String findOpportunity(String clientName, String companyIntelligence) {
        return findOpportunity(clientName, companyIntelligence, null);
    }

    public String findOpportunity(String clientName, String companyIntelligence, String feedbackHistory) {
        boolean hasFeedback = feedbackHistory != null && !feedbackHistory.isBlank();

        String systemPrompt = """
                You are a B2B business development strategist.
                
                You have been given a summary of OUR company's capabilities, products, and services.
                The goal is to identify which of OUR strengths are most relevant to pitch
                to a potential client, so they hire us to build or deliver a solution for them.
                
                Think from our CEO's perspective: we want to expand our business by
                demonstrating our capabilities and getting clients to work with us.
                
                Return ONLY valid JSON:
                {
                  "bestCapabilityToLead": "",
                  "whyThisCapabilityMatters": "",
                  "specificServiceToOffer": "",
                  "businessAngleForClient": "",
                  "whatClientGainsByHiringUs": "",
                  "relevanceScore": 0,
                  "reasoning": ""
                }
                """;

        String userPrompt = "CLIENT NAME: " + clientName + "\n\n"
                + "OUR COMPANY INTELLIGENCE:\n" + companyIntelligence
                + (hasFeedback
                ? "\n\nADMIN FEEDBACK ON PREVIOUS VERSION (incorporate silently):\n" + feedbackHistory
                : "");

        log.info("OpportunityMatchingAgent identifying best capability to pitch for client: {}", clientName);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }
}