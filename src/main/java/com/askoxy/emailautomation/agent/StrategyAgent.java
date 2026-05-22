package com.askoxy.emailautomation.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyAgent {

    private final ChatClient chatClient;

    public String buildStrategy(String clientName, String opportunityResult) {
        return buildStrategy(clientName, opportunityResult, null);
    }

    public String buildStrategy(String clientName, String opportunityResult, String feedbackHistory) {
        boolean hasFeedback = feedbackHistory != null && !feedbackHistory.isBlank();

        String systemPrompt = """
                You are a senior GTM strategist helping a company write outreach emails
                to attract new clients.
                
                Your company's CEO wants to expand the business by reaching out to potential clients
                and showing them what your company can build or deliver for them.
                
                Build a specific email strategy based on OUR company's capabilities.
                The email must:
                - Introduce who we are and what we do (briefly, specifically)
                - Highlight 1-2 specific things we have built or can deliver
                - Make a clear case for why the client should work with us
                - End with a low-friction CTA (15-min call, quick chat)
                - Sound like a founder reaching out, not a sales rep
                
                Do NOT make it about the client's pain points — make it about OUR capability
                and what we can BUILD or DELIVER for them.
                
                Return ONLY valid JSON:
                {
                  "emailOpener": "",
                  "ourCompanyIntro": "",
                  "capabilityToHighlight": "",
                  "whatWeCanBuildForThem": "",
                  "whyWorkWithUs": "",
                  "callToAction": "",
                  "tone": "",
                  "thingsToAvoid": [],
                  "reasoning": ""
                }
                """;

        String userPrompt = "CLIENT NAME: " + clientName + "\n\n"
                + "OPPORTUNITY ANALYSIS:\n" + opportunityResult
                + (hasFeedback
                ? "\n\nADMIN FEEDBACK ON PREVIOUS VERSION (incorporate silently):\n" + feedbackHistory
                : "");

        log.info("StrategyAgent building outreach strategy for client: {}", clientName);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }
}