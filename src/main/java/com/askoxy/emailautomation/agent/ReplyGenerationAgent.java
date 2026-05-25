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
public class ReplyGenerationAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are Alex, a representative of our company, replying to an incoming email from a potential client.

            Your goal is to:
            1. Acknowledge what the client said - directly and specifically, not generically
            2. Answer any questions they raised using facts from our company context
            3. Move the conversation forward toward a call, demo, or agreement to work together
            4. Keep it warm, confident, and brief - like a real person, not a template

            STRICT RULES:
            - Max 150 words in body
            - Reference what the client specifically said - do NOT write a generic reply
            - If the client asked multiple questions, answer each explicitly
            - If the client raised an objection (price/timeline/scope/trust), address it directly with a concrete response
            - Use "we", "our team", "we've built" - not "I" statements
            - Be specific - pull real facts from the company context provided
            - NEVER use: innovative, cutting-edge, seamlessly, leverage, synergy, world-class
            - NEVER use generic openers like "Thank you for your email" or "I hope you're well"
            - Open with something that directly addresses their message
            - Include 1-2 concrete next steps tied to what they asked
            - One clear CTA at the end (book a call, share a demo, answer a specific question)
            - Sign-off: "Alex" only
            - Reply subject: keep the same subject with "Re: " prefix if not already there

            SOURCE-OF-TRUTH RULES:
            - PRIMARY INPUT: CLIENT'S REPLY. Your response must map to what the client asked or objected to.
            - SECONDARY INPUT: ADMIN FEEDBACK. Every feedback point is mandatory and must be applied.
            - COMPANY CONTEXT is supporting material only. Never invent facts not present in context.
            - If admin feedback conflicts with style rules, apply admin feedback.

            OUTPUT FORMAT - CRITICAL:
            - Your ENTIRE response must be a single JSON object
            - No preamble, no explanation, no markdown fences
            - Start with { and end with }

            REQUIRED JSON FORMAT:
            {"subject": "Re: subject here", "body": "reply body here"}
            """;

    public GeneratedEmailDto generateReply(String clientName,
                                           String clientReplyText,
                                           String originalSubject,
                                           String companyContext,
                                           String feedbackHistory) {

        String safeClientReply = clientReplyText == null ? "" : clientReplyText.trim();
        String safeFeedback = feedbackHistory == null ? "" : feedbackHistory.trim();
        boolean hasFeedback = !safeFeedback.isBlank();

        String systemPrompt = hasFeedback
                ? SYSTEM_PROMPT
                + "\n\n====== ADMIN REVISION INSTRUCTIONS (MANDATORY) ======\n"
                + "The admin rejected the previous version. You MUST incorporate all of the following:\n\n"
                + safeFeedback
                + "\n\nIf any base rule above conflicts with admin feedback, ALWAYS follow admin feedback."
                + "\nDo NOT acknowledge the feedback in your reply. Just silently write the improved version."
                + "\n====== END REVISION INSTRUCTIONS ======"
                : SYSTEM_PROMPT;

        String userPrompt = "CLIENT NAME: " + clientName + "\n\n"
                + "ORIGINAL EMAIL SUBJECT WE SENT:\n" + originalSubject + "\n\n"
                + "CLIENT'S REPLY (what they wrote to us):\n" + safeClientReply + "\n\n"
                + "OUR COMPANY CONTEXT (use specific facts from here to answer their questions):\n"
                + companyContext + "\n\n"
                + (hasFeedback
                ? "ADMIN FEEDBACK TO APPLY (mandatory):\n" + safeFeedback + "\n\n"
                : "")
                + "Write a reply to their email now."
                + "\nBefore writing, extract the client asks/objections from CLIENT'S REPLY and address each in the body."
                + "\nIf admin feedback exists, apply every point in final output."
                + "\nBe specific, warm, direct, and non-generic."
                + "\nReturn JSON only.";

        log.info("[ReplyGenerationAgent] Generating reply - client='{}' hasFeedback={} feedbackLength={} clientReplyLength={}",
                clientName, hasFeedback, hasFeedback ? safeFeedback.length() : 0, safeClientReply.length());

        if (hasFeedback) {
            log.info("[ReplyGenerationAgent] Feedback being applied: '{}'", safeFeedback);
        }

        log.debug("[ReplyGenerationAgent] System prompt (last 300 chars): {}",
                systemPrompt.substring(Math.max(0, systemPrompt.length() - 300)));

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        log.info("[ReplyGenerationAgent] Raw response received - length={}", response != null ? response.length() : 0);
        log.debug("[ReplyGenerationAgent] Raw response: {}", response);

        return parseResponse(response, clientName, originalSubject);
    }

    private GeneratedEmailDto parseResponse(String response, String clientName, String originalSubject) {
        String cleaned = (response == null ? "" : response)
                .replaceAll("(?i)```json", "")
                .replaceAll("```", "")
                .trim();

        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (node.has("subject") && node.has("body")) {
                return GeneratedEmailDto.builder()
                        .subject(node.get("subject").asText())
                        .body(node.get("body").asText())
                        .build();
            }
        } catch (Exception ignored) {
        }

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
        } catch (Exception ignored) {
        }

        log.error("[ReplyGenerationAgent] Could not parse JSON for client={}. Raw: {}", clientName, response);
        return GeneratedEmailDto.builder()
                .subject("Re: " + originalSubject)
                .body(cleaned.isBlank() ? "Could you share a bit more detail so we can respond precisely?" : cleaned)
                .build();
    }
}
