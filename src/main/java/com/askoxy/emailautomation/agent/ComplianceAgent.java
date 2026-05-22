package com.askoxy.emailautomation.agent;

import com.askoxy.emailautomation.dto.GeneratedEmailDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ComplianceAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public GeneratedEmailDto reviewAndRefine(GeneratedEmailDto email) {
        return reviewAndRefine(email, null, false);
    }

    public GeneratedEmailDto reviewAndRefine(GeneratedEmailDto email, String feedbackHistory) {
        return reviewAndRefine(email, feedbackHistory, false);
    }

    public GeneratedEmailDto reviewAndRefine(GeneratedEmailDto email, String feedbackHistory, boolean isReplyEmail) {
        boolean hasFeedback = feedbackHistory != null && !feedbackHistory.isBlank();

        String basePrompt = isReplyEmail
                ? """
                You are a B2B email editor reviewing a reply email to an incoming client message.

                Check and fix:
                1. Reply must directly address the client's actual message (no generic template tone)
                2. Remove ANY generic openers ("I hope", "I wanted to reach out", "I am writing to")
                3. Replace ANY placeholder like [Your Name], [Position] with "Alex"
                4. Kill buzzwords: innovative, cutting-edge, seamlessly, leverage, synergy, transform
                5. Ensure body is under 150 words
                6. Ensure the email clearly says what WE do and what WE can BUILD for the client
                7. Make sure there is exactly ONE clear low-friction CTA
                8. Keep subject as a proper reply subject (must retain "Re:" context)
                9. Tone: confident founder, not a salesperson

                Return ONLY valid JSON:
                {"subject": "", "body": ""}
                """
                : """
                You are a B2B email editor reviewing an outreach email written by our company
                to attract a potential client to hire us.
                
                Check and fix:
                1. Remove ANY generic openers ("I hope", "I wanted to reach out", "I am writing to")
                2. Replace ANY placeholder like [Your Name], [Position] with "Alex"
                3. Kill buzzwords: innovative, cutting-edge, seamlessly, leverage, synergy, transform
                4. Ensure body is under 150 words
                5. Ensure the email clearly says what WE do and what WE can BUILD for the client
                6. Make sure there is exactly ONE clear low-friction CTA
                7. First sentence must be specific — who we are or what we do
                8. Subject must be under 8 words and make them want to open it
                9. Tone: confident founder, not a salesperson
                
                Return ONLY valid JSON:
                {"subject": "", "body": ""}
                """;

        String systemPrompt = basePrompt
                + (hasFeedback
                ? "\nADMIN FEEDBACK IS MANDATORY. Treat it as strict requirements and highest priority."
                + "\nIf any generic style rule conflicts with feedback, follow feedback."
                : "");

        // Concatenation — not .formatted() — email body may contain % characters
        String userPrompt = "REVIEW THIS EMAIL:\n\nSubject: " + email.getSubject()
                + "\n\nBody:\n" + email.getBody()
                + (hasFeedback
                ? "\n\nADMIN FEEDBACK TO ALSO ADDRESS:\n" + feedbackHistory
                : "")
                + "\n\nFix all issues. Return the improved version as JSON only.";

        log.info("ComplianceAgent reviewing email");

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        try {
            String cleaned = response
                    .replaceAll("(?i)```json", "")
                    .replaceAll("```", "")
                    .trim();
            JsonNode node = objectMapper.readTree(cleaned);
            return GeneratedEmailDto.builder()
                    .subject(node.get("subject").asText())
                    .body(node.get("body").asText())
                    .build();
        } catch (Exception ex) {
            log.warn("ComplianceAgent parse failed, returning original email", ex);
            return email; // safe fallback
        }
    }
}
