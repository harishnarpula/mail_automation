package com.askoxy.emailautomation.agent;

import com.askoxy.emailautomation.dto.GeneratedEmailDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailGenerationAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are writing a B2B outreach email on behalf of OUR company to a potential client.
            
            The goal of this email — as directed by our CEO — is to:
            1. Introduce our company briefly and specifically
            2. Showcase what we have built and what we can build for the client
            3. Make the client want to hire us or explore working with us
            4. Get a reply or a short call booked
            
            EMAIL STRUCTURE:
            - Greeting: Use the client's first name once
            - Line 1-2: Who we are and what we do (specific, not generic)
            - Line 3-4: What we have built / what we can build or deliver for them
            - Line 5: Why working with us makes sense (one concrete reason)
            - Line 6: Single CTA — low friction (15-min call, quick question)
            - Sign-off: First name only ("Alex")
            
            STRICT RULES:
            - Max 150 words in body
            - Use "we have", "we build", "we deliver" — not "I" statements
            - Be specific — pull real facts from the company context
            - NEVER use: innovative, cutting-edge, seamlessly, leverage, synergy, world-class
            - NEVER use generic openers like "I hope this finds you well"
            - NEVER use placeholder text like [Your Name] — use "Alex"
            - Subject line: specific, under 8 words, makes them want to open it
            - Sound like a real founder, not a marketing template
            
            OUTPUT FORMAT — CRITICAL:
            - Your ENTIRE response must be a single JSON object
            - No preamble, no explanation, no markdown fences
            - Start your response with { and end with }
            
            REQUIRED JSON FORMAT:
            {"subject": "subject here", "body": "email body here"}
            """;

    public GeneratedEmailDto generateEmail(String clientName, String companyIntelligence,
                                           String strategy, String retrievedContext) {
        return generateEmail(clientName, companyIntelligence, strategy, retrievedContext, null);
    }

    public GeneratedEmailDto generateEmail(String clientName, String companyIntelligence,
                                           String strategy, String retrievedContext,
                                           String feedbackHistory) {
        boolean hasFeedback = feedbackHistory != null && !feedbackHistory.isBlank();

        // Inject feedback into system prompt — keeps it as instruction, not data
        String systemPrompt = hasFeedback
                ? SYSTEM_PROMPT + "\nREVISION INSTRUCTIONS (admin rejected previous version):\n"
                  + feedbackHistory
                  + "\nIncorporate ALL feedback silently. Do NOT acknowledge it. Just write the improved email as JSON."
                : SYSTEM_PROMPT;

        // Build user prompt via concatenation — never .formatted() on external data
        String userPrompt = "CLIENT NAME: " + clientName + "\n\n"
                + "OUR COMPANY INTELLIGENCE:\n" + companyIntelligence + "\n\n"
                + "EMAIL STRATEGY:\n" + strategy + "\n\n"
                + "RAW COMPANY CONTEXT (use specific facts from here):\n" + retrievedContext + "\n\n"
                + "Write the email now. Be specific about what WE do and what WE can build for them.";

        log.info("EmailGenerationAgent generating email for client: {} | hasFeedback={}", clientName, hasFeedback);

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        log.debug("EmailGenerationAgent raw response: {}", response);
        return parseResponse(response, clientName);
    }

    private GeneratedEmailDto parseResponse(String response, String clientName) {
        String cleaned = response
                .replaceAll("(?i)```json", "")
                .replaceAll("```", "")
                .trim();

        // Pass 1: clean JSON
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (node.has("subject") && node.has("body")) {
                return GeneratedEmailDto.builder()
                        .subject(node.get("subject").asText())
                        .body(node.get("body").asText())
                        .build();
            }
        } catch (Exception ignored) {}

        // Pass 2: JSON buried in prose
        try {
            Matcher matcher = Pattern.compile("\\{[^{}]*\"subject\"[^{}]*\"body\"[^{}]*\\}",
                    Pattern.DOTALL).matcher(cleaned);
            if (matcher.find()) {
                JsonNode node = objectMapper.readTree(matcher.group());
                log.warn("EmailGenerationAgent: extracted JSON from prose for client={}", clientName);
                return GeneratedEmailDto.builder()
                        .subject(node.get("subject").asText())
                        .body(node.get("body").asText())
                        .build();
            }
        } catch (Exception ignored) {}

        // Pass 3: degrade gracefully — never crash
        log.error("EmailGenerationAgent: could not parse JSON for client={}. Raw: {}", clientName, response);
        return GeneratedEmailDto.builder()
                .subject("Introduction from our team — " + clientName)
                .body(response.trim())
                .build();
    }
}