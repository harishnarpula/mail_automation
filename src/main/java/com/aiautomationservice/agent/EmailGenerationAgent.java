package com.aiautomationservice.agent;

import com.aiautomationservice.dto.GeneratedEmailDto;
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
            You are writing a formal B2B introduction email on behalf of OXYGLOBAL TECHNOLOGIES,
            sent personally by Radhakrishna Thatavarti (Founder & CEO) to a potential client or partner.

            This is NOT a short cold email. This is a full company introduction email —
            structured, professional, and detailed — like a CEO personally writing to a
            senior decision-maker.

            ALL CONTENT MUST COME FROM THE COMPANY DOCUMENTS PROVIDED.
            Do NOT invent, assume, or add any facts not present in the documents.
            Every product name, platform name, figure, statistic, and capability must
            be extracted directly from the retrieved company context.

            EMAIL STRUCTURE — follow this order exactly:

            1. GREETING:
               Dear [ClientFirstName],

            2. OPENING:
               Greetings from Radhakrishna Thatavarti,
               One sentence introducing the company — use the company description from the documents.

            3. SECTION HEADING: OUR CORE ECOSYSTEM
               Write what the company does across its key pillars — use whatever structure
               the documents describe (e.g. People, Platforms, Products, caPital or similar).
               Each pillar gets its own paragraph. Use facts, names, and figures from the documents.

            4. SECTION HEADING: BFSI AND ENTERPRISE EXPERTISE
               Write about the company's domain expertise — use only what is in the documents
               (banking systems, industries served, client types, etc.)

            5. SECTION HEADING: LEADERSHIP
               Write about the founders and leadership team using only details from the documents.

            6. CLOSING:
               Express desire to meet, present a live demonstration, and explore collaboration.
               Personalise this line to the client's name.

            7. SIGN-OFF:
               Warm regards,
               Radhakrishna Thatavarti
               Founder & CEO
               OXYGLOBAL TECHNOLOGIES
               www.oxyglobal.tech

            STRICT RULES:
            - NO emojis anywhere in the email
            - NO bullet points — write in flowing paragraphs only
            - NO hardcoded facts — every fact must come from the company context provided
            - Formal business language — CEO writing to a senior executive
            - Do NOT use: innovative, cutting-edge, seamlessly, leverage, synergy, world-class
            - Do NOT use generic openers like "I hope this finds you well"
            - Subject line: formal, specific, references what we do, under 10 words
            - Each section separated by a blank line
            - Client name must appear exactly as provided — do NOT alter it

            OUTPUT FORMAT — CRITICAL:
            - Your ENTIRE response must be a single JSON object
            - No preamble, no explanation, no markdown fences
            - Start your response with { and end with }

            REQUIRED JSON FORMAT:
            {"subject": "subject here", "body": "email body here"}
            """;

    private static final String BULK_SYSTEM_PROMPT = """
            You are writing a formal B2B introduction email on behalf of OXYGLOBAL TECHNOLOGIES,
            sent personally by Radhakrishna Thatavarti (Founder & CEO) to multiple potential
            clients or partners.

            This is NOT a short cold email. This is a full company introduction email —
            structured, professional, and detailed.

            ALL CONTENT MUST COME FROM THE COMPANY DOCUMENTS PROVIDED.
            Do NOT invent, assume, or add any facts not present in the documents.

            EMAIL STRUCTURE — follow this order exactly:

            1. GREETING:
               Dear {clientName},

            2. OPENING:
               Greetings from Radhakrishna Thatavarti,
               One sentence introducing the company — use the company description from the documents.

            3. SECTION HEADING: OUR CORE ECOSYSTEM
               Write what the company does across its key pillars using facts from the documents.
               Each pillar gets its own paragraph.

            4. SECTION HEADING: BFSI AND ENTERPRISE EXPERTISE
               Write about domain expertise using only what is in the documents.

            5. SECTION HEADING: LEADERSHIP
               Write about founders and leadership using only details from the documents.

            6. CLOSING:
               Express desire to meet, demonstrate, and collaborate.

            7. SIGN-OFF:
               Warm regards,
               Radhakrishna Thatavarti
               Founder & CEO
               OXYGLOBAL TECHNOLOGIES
               www.oxyglobal.tech

            PLACEHOLDER RULES — CRITICAL:
            - Use {clientName} wherever the client's first name appears
            - Use {clientCompany} wherever the client's company appears
            - Never use actual names — replaced at send time

            STRICT RULES:
            - NO emojis anywhere in the email
            - NO bullet points — write in flowing paragraphs only
            - NO hardcoded facts — every fact must come from the company context provided
            - Formal business language
            - Do NOT use: innovative, cutting-edge, seamlessly, leverage, synergy, world-class
            - Each section separated by a blank line

            OUTPUT FORMAT — CRITICAL:
            - Your ENTIRE response must be a single JSON object
            - No preamble, no explanation, no markdown fences
            - Start with { and end with }

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

        String systemPrompt = hasFeedback
                ? SYSTEM_PROMPT + "\nREVISION INSTRUCTIONS (admin rejected previous version):\n"
                  + feedbackHistory
                  + "\nIncorporate ALL feedback silently. Do NOT acknowledge it. Just write the improved email as JSON."
                : SYSTEM_PROMPT;

        String userPrompt = "CLIENT NAME: " + clientName + "\n\n"
                + "COMPANY INTELLIGENCE (extracted from our documents):\n" + companyIntelligence + "\n\n"
                + "EMAIL STRATEGY:\n" + strategy + "\n\n"
                + "RAW COMPANY CONTEXT — use ONLY facts from here, nothing else:\n" + retrievedContext + "\n\n"
                + "Write the full introduction email now.\n"
                + "Every product, platform, figure and capability must come from the context above.\n"
                + "No emojis. No bullet points. Each section in its own paragraph.";

        log.info("[EmailGenAgent] Generating introduction email for client={} hasFeedback={}", clientName, hasFeedback);

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        log.debug("[EmailGenAgent] Raw response: {}", response);
        return parseResponse(response, clientName);
    }

    public GeneratedEmailDto generateBulkEmail(String previewClientName,
                                               String previewClientCompany,
                                               String intelligence,
                                               String strategy,
                                               String context) {
        String userPrompt = "COMPANY INTELLIGENCE (extracted from our documents):\n" + intelligence + "\n\n"
                + "EMAIL STRATEGY:\n" + strategy + "\n\n"
                + "RAW COMPANY CONTEXT — use ONLY facts from here, nothing else:\n" + context + "\n\n"
                + "REMINDER: Use {clientName} and {clientCompany} as placeholders — never real names.\n\n"
                + "Write the full introduction email template now. No emojis. No bullet points.";

        log.info("[EmailGenAgent] Generating bulk email template. previewClient={} company={}",
                previewClientName, previewClientCompany);

        String response = chatClient.prompt()
                .system(BULK_SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        log.debug("[EmailGenAgent] Bulk raw response: {}", response);

        GeneratedEmailDto result = parseResponse(response, previewClientName);

        if (!result.getBody().contains("{clientName}")) {
            log.warn("[EmailGenAgent] AI did not use {{clientName}} placeholder — forcing it in greeting.");
            String fixedBody = "Dear {clientName},\n\n" + result.getBody();
            result = GeneratedEmailDto.builder()
                    .subject(result.getSubject())
                    .body(fixedBody)
                    .build();
        }

        return result;
    }

    private GeneratedEmailDto parseResponse(String response, String clientName) {
        String cleaned = response
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
        } catch (Exception ignored) {}

        try {
            Matcher matcher = Pattern.compile("\\{[^{}]*\"subject\"[^{}]*\"body\"[^{}]*\\}",
                    Pattern.DOTALL).matcher(cleaned);
            if (matcher.find()) {
                JsonNode node = objectMapper.readTree(matcher.group());
                log.warn("[EmailGenAgent] Extracted JSON from prose for client={}", clientName);
                return GeneratedEmailDto.builder()
                        .subject(node.get("subject").asText())
                        .body(node.get("body").asText())
                        .build();
            }
        } catch (Exception ignored) {}

        log.error("[EmailGenAgent] Could not parse JSON for client={}. Raw: {}", clientName, response);
        return GeneratedEmailDto.builder()
                .subject("Introduction from OXYGLOBAL TECHNOLOGIES")
                .body(response.trim())
                .build();
    }
}