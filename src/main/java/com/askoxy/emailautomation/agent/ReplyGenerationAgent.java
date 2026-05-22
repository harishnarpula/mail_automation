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

/**
 * Generates a reply email to a client's incoming message.
 *
 * Unlike EmailGenerationAgent (cold outreach), this agent:
 * - Reads what the client actually said
 * - Understands our company context from RAG
 * - Crafts a conversational, helpful reply that moves toward a deal/call
 * - Maintains the same "Alex" persona throughout the conversation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplyGenerationAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are Alex, a representative of our company, replying to an incoming email from a potential client.
            
            Your goal is to:
            1. Acknowledge what the client said — directly and specifically, not generically
            2. Answer any questions they raised using facts from our company context
            3. Move the conversation forward toward a call, demo, or agreement to work together
            4. Keep it warm, confident, and brief — like a real person, not a template
            
            STRICT RULES:
            - Max 150 words in body
            - Reference what the client specifically said — do NOT write a generic reply
            - If the client asked multiple questions, answer each explicitly
            - If the client raised an objection (price/timeline/scope/trust), address it directly with a concrete response
            - Use "we", "our team", "we've built" — not "I" statements
            - Be specific — pull real facts from the company context provided
            - NEVER use: innovative, cutting-edge, seamlessly, leverage, synergy, world-class
            - NEVER use generic openers like "Thank you for your email" or "I hope you're well"
            - Open with something that directly addresses their message
            - Include 1-2 concrete next steps tied to what they asked
            - One clear CTA at the end (book a call, share a demo, answer a specific question)
            - Sign-off: "Alex" only
            - Reply subject: keep the same subject with "Re: " prefix if not already there
            
            OUTPUT FORMAT — CRITICAL:
            - Your ENTIRE response must be a single JSON object
            - No preamble, no explanation, no markdown fences
            - Start with { and end with }
            
            REQUIRED JSON FORMAT:
            {"subject": "Re: subject here", "body": "reply body here"}
            """;

    /**
     * @param clientName        Name of the client
     * @param clientReplyText   The raw text of the client's reply email
     * @param originalSubject   Subject of the original email we sent
     * @param companyContext    RAG-retrieved company knowledge
     * @param feedbackHistory   Admin feedback from previous regeneration rounds (nullable)
     */
    public GeneratedEmailDto generateReply(String clientName,
                                           String clientReplyText,
                                           String originalSubject,
                                           String companyContext,
                                           String feedbackHistory) {

        boolean hasFeedback = feedbackHistory != null && !feedbackHistory.isBlank();

        String systemPrompt = hasFeedback
                ? SYSTEM_PROMPT
                  + "\nREVISION INSTRUCTIONS (admin rejected previous version):\n"
                  + feedbackHistory
                  + "\nTreat admin feedback as strict requirements and highest priority."
                  + "\nIf any earlier style rule conflicts with feedback, follow feedback."
                  + "\nIncorporate ALL feedback silently. Do NOT acknowledge it. Just write the improved reply as JSON."
                : SYSTEM_PROMPT;

        // Build user prompt — concatenation only, no .formatted() on external data
        String userPrompt = "CLIENT NAME: " + clientName + "\n\n"
                + "ORIGINAL EMAIL SUBJECT WE SENT:\n" + originalSubject + "\n\n"
                + "CLIENT'S REPLY (what they wrote to us):\n" + clientReplyText + "\n\n"
                + "OUR COMPANY CONTEXT (use specific facts from here to answer their questions):\n"
                + companyContext + "\n\n"
                + "Write a reply to their email now."
                + "\nFirst, understand what they asked."
                + "\nThen answer those exact points with concrete details."
                + "\nBe specific, warm, direct, and non-generic.";

        log.info("[ReplyGenerationAgent] Generating reply for client={} | hasFeedback={}", clientName, hasFeedback);

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        log.debug("[ReplyGenerationAgent] Raw response: {}", response);
        return parseResponse(response, clientName, originalSubject);
    }

    private GeneratedEmailDto parseResponse(String response, String clientName, String originalSubject) {
        String cleaned = response
                .replaceAll("(?i)```json", "")
                .replaceAll("```", "")
                .trim();

        // Pass 1: clean JSON parse
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
                log.warn("[ReplyGenerationAgent] Extracted JSON from prose for client={}", clientName);
                return GeneratedEmailDto.builder()
                        .subject(node.get("subject").asText())
                        .body(node.get("body").asText())
                        .build();
            }
        } catch (Exception ignored) {}

        // Pass 3: graceful fallback — never crash
        log.error("[ReplyGenerationAgent] Could not parse JSON for client={}. Raw: {}", clientName, response);
        return GeneratedEmailDto.builder()
                .subject("Re: " + originalSubject)
                .body(response.trim())
                .build();
    }
}
