package com.askoxy.emailautomation.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClientIntelligenceAgent {

    private final ChatClient chatClient;

    public String analyze(String clientName, String retrievedContext) {
        return analyze(retrievedContext, clientName, null);
    }

    public String analyze(String ragContext, String clientName, String feedbackHistory) {
        boolean hasFeedback = feedbackHistory != null && !feedbackHistory.isBlank();

        String systemPrompt = """
                You are a company intelligence extractor.
                
                You are given content from OUR company's documents (brochures, decks, product docs).
                Your job is to extract clear, specific facts about OUR company that can be used
                to introduce ourselves to a potential client named """ + clientName + """
                
                Extract ONLY what is explicitly stated in the document. Do NOT invent or assume anything.
                
                Return ONLY valid JSON:
                {
                  "companyName": "",
                  "whatWeDo": "",
                  "ourProducts": [],
                  "ourServices": [],
                  "ourCapabilities": [],
                  "ourDifferentiators": [],
                  "industriesWeServe": [],
                  "notableAchievements": [],
                  "technologyStack": []
                }
                """;

        String userPrompt = "OUR COMPANY DOCUMENTS:\n" + ragContext
                + (hasFeedback
                ? "\n\nADMIN FEEDBACK ON PREVIOUS VERSION (incorporate silently):\n" + feedbackHistory
                : "");

        log.info("ClientIntelligenceAgent analyzing our company docs for client: {} | hasFeedback={}",
                clientName, hasFeedback);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }
}