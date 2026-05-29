package com.aiautomationservice.agent;

import com.aiautomationservice.dto.GeneratedEmailDto;
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
                1. Reply must directly address the client's actual message — no generic template tone
                2. Remove ANY generic openers ("I hope", "I wanted to reach out", "I am writing to")
                3. Replace ANY placeholder like [Your Name], [Position] with "Radhakrishna Thatavarti"
                4. Remove all emojis from subject and body
                5. Remove all bullet points — convert to flowing paragraphs
                6. Kill buzzwords: innovative, cutting-edge, seamlessly, leverage, synergy, transform
                7. Ensure the email clearly says what WE do and what WE can BUILD for the client
                8. Make sure there is exactly ONE clear low-friction CTA
                9. Keep subject as a proper reply subject (must retain "Re:" context)
                10. Tone: formal, confident CEO writing to a senior executive

                DO NOT shorten the email — preserve all content and sections.

                Return ONLY valid JSON:
                {"subject": "", "body": ""}
                """
                : """
                You are a B2B email editor reviewing a formal company introduction email written
                by Radhakrishna Thatavarti (CEO, OXYGLOBAL TECHNOLOGIES) to a potential client.

                Check and fix:
                1. Remove ANY generic openers ("I hope", "I wanted to reach out")
                2. Replace ANY placeholder like [Your Name], [Position] with "Radhakrishna Thatavarti"
                3. Remove all emojis from subject and body
                4. Remove all bullet points — convert to flowing paragraphs
                5. Kill buzzwords: innovative, cutting-edge, seamlessly, leverage, synergy, transform
                6. Ensure all four sections are present: 4P Ecosystem, BFSI Expertise, Leadership, Closing
                7. Subject must be formal and under 10 words
                8. Tone: formal CEO writing personally to a senior executive
                9. Sign-off must be: Warm regards, / Radhakrishna Thatavarti / Founder & CEO / OXYGLOBAL TECHNOLOGIES / www.oxyglobal.tech

                DO NOT shorten the email — this is a full introduction, not a cold pitch.
                Preserve all sections and content. Only fix style and compliance issues.

                Return ONLY valid JSON:
                {"subject": "", "body": ""}
                """;

        String systemPrompt = basePrompt
                + (hasFeedback
                ? "\nADMIN FEEDBACK IS MANDATORY. Treat it as strict requirements and highest priority."
                  + "\nIf any generic style rule conflicts with feedback, follow feedback."
                : "");

        String userPrompt = "REVIEW THIS EMAIL:\n\nSubject: " + email.getSubject()
                + "\n\nBody:\n" + email.getBody()
                + (hasFeedback
                ? "\n\nADMIN FEEDBACK TO ALSO ADDRESS:\n" + feedbackHistory
                : "")
                + "\n\nFix all issues. Preserve all content and sections. Return the improved version as JSON only.";

        log.info("[ComplianceAgent] Reviewing email — isReplyEmail={} hasFeedback={}", isReplyEmail, hasFeedback);

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
            log.warn("[ComplianceAgent] Parse failed, returning original email", ex);
            return email;
        }
    }
}