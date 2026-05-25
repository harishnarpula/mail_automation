package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.dto.*;
import com.askoxy.emailautomation.entity.ContentItem;
import com.askoxy.emailautomation.enums.ContentStatus;
import com.askoxy.emailautomation.enums.PlatformType;
import com.askoxy.emailautomation.repository.ContentItemRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class ContentService {

    private final AIService aiService;
    private final DocumentService documentService;
    private final IngestionService ingestionService;
    private final ContentItemRepository contentItemRepository;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final S3Service s3Service;

    public ContentService(AIService aiService, DocumentService documentService, IngestionService ingestionService, ContentItemRepository contentItemRepository, @org.springframework.beans.factory.annotation.Qualifier("radhaAiVectorStore") VectorStore vectorStore, ObjectMapper objectMapper, S3Service s3Service) {
        this.aiService = aiService;
        this.documentService = documentService;
        this.ingestionService = ingestionService;
        this.contentItemRepository = contentItemRepository;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.s3Service = s3Service;
    }

    private static final String REASONING_SYSTEM = """
            You are an expert content strategist and editor for AskOxy Group.

            Your job is to:
            1. READ the raw CEO input carefully — it may come from voice transcription
               or quick typing and MAY contain spelling mistakes, grammar errors,
               or unclear phrasing.
            2. CORRECT all spelling mistakes, grammar errors, and unclear phrasing.
            3. EXTRACT the core business idea(s) clearly.
            4. DECIDE if all ideas are about the same topic (sameTopicGroup=true)
               or multiple different topics (sameTopicGroup=false).

            Return ONLY valid JSON — no explanation, no markdown, no extra text:
            {
              "cleanedIdea": "corrected and clarified version of the CEO input",
              "sameTopicGroup": true
            }

            Rules for cleanedIdea:
            - Fix ALL spelling mistakes (especially from voice: "oxyloanz" → "OxyLoans")
            - Fix grammar but KEEP the original meaning
            - Keep business terms exact: OxyLoans, OxyGold.ai, OxyBricks, AskOxy.AI,
              StudyAbroad, OxyGlobal, Radha Sir, AskOxy Group
            - If input mentions multiple unrelated topics → sameTopicGroup: false
            - If input is about one topic or closely related topics → sameTopicGroup: true
            """;

    private static final String GENERATION_SYSTEM_GROUPED = """
            You are an expert content writer for AskOxy Group, writing on behalf of
            Radha Krishna Thatavrthi, CEO & Founder.

            Generate rich, professional business content based on the CEO's idea.
            Use the provided knowledge base context to add accurate facts and details.

            SPELLING & GRAMMAR RULES (critical):
            - Zero spelling mistakes allowed in the output
            - Zero grammar mistakes allowed in the output
            - Business terms must be exact: OxyLoans, OxyGold.ai, OxyBricks,
              AskOxy.AI, StudyAbroad, OxyGlobal, Radha Sir, AskOxy Group
            - Numbers and percentages must be accurate — do NOT invent statistics
            - If a fact is not in the knowledge base, do NOT include it

            CONTENT RULES:
            - Tone: confident, professional, warm — as Radha Sir would speak
            - First person: "We at OxyLoans..." or "I'm proud to announce..."
            - Value-driven: always explain what benefit the user gets
            - Avoid corporate jargon — write simply and clearly

            Return ONLY valid JSON — no explanation, no markdown fences:
            {
              "title": "compelling headline — 6-12 words",
              "summary": "2-3 sentence overview of what this content is about",
              "intro": "1-2 sentence hook that grabs attention",
              "body": "main content — 150 to 300 words — well written, zero errors, first person as Radha Sir",
              "closing": "1-2 sentence strong closing thought",
              "isGrouped": true
            }
            """;

    private static final String GENERATION_SYSTEM_SEPARATE = """
            You are an expert content writer for AskOxy Group, writing on behalf of
            Radha Krishna Thatavrthi, CEO & Founder.

            The CEO has given input covering MULTIPLE different topics.
            Generate separate focused content for each topic.
            Use the knowledge base context to add accurate facts.

            SPELLING & GRAMMAR RULES (critical):
            - Zero spelling mistakes allowed in the output
            - Zero grammar mistakes allowed in the output
            - Business terms must be exact: OxyLoans, OxyGold.ai, OxyBricks,
              AskOxy.AI, StudyAbroad, OxyGlobal, Radha Sir, AskOxy Group
            - Do NOT invent statistics or facts not in the knowledge base

            CONTENT RULES:
            - Tone: confident, professional, warm — as Radha Sir would speak
            - First person: "We at OxyLoans..." or "I believe..."
            - Each section should stand alone as a complete piece of content
            - 100-200 words per section

            Return ONLY valid JSON — no explanation, no markdown fences:
            {
              "title": "overall theme title — 6-12 words",
              "summary": "2-3 sentence overview connecting all topics",
              "intro": "1-2 sentence overall hook",
              "body": "brief intro connecting all topics — 80-120 words, first person as Radha Sir",
              "sections": [
                {
                  "heading": "Topic 1 title",
                  "content": "Topic 1 content — 100-200 words, zero errors, first person as Radha Sir"
                }
              ],
              "closing": "1-2 sentence strong closing thought from Radha Sir",
              "isGrouped": false
            }
            """;

    @Transactional
    public ContentItem submit(ContentDto req) throws Exception {

        log.info("Content pipeline started — platform={}", req.getPlatform());

        String textInput = "";
        if (req.getRawInstruction() != null && !req.getRawInstruction().isBlank())
            textInput = normalizeText(req.getRawInstruction());

        String voiceInput = "";
        if (req.getVoiceFile() != null && !req.getVoiceFile().isEmpty()) {
            log.info("Transcribing voice file");
            voiceInput = normalizeText(aiService.transcribe(req.getVoiceFile()));
            log.info("VOICE TRANSCRIPT:\n{}", voiceInput);
        }

        String fileInput = "";
        if (req.getAttachment() != null && !req.getAttachment().isEmpty()) {
            fileInput = smartSummarizeFile(req.getAttachment());
            log.info("FILE SUMMARY:\n{}", fileInput);
        }

        String imageUrl = null;
        if (req.getAttachment() != null && !req.getAttachment().isEmpty()) {
            String name = req.getAttachment().getOriginalFilename().toLowerCase();
            if (name.endsWith(".png") || name.endsWith(".jpg")
                    || name.endsWith(".jpeg") || name.endsWith(".webp")) {
                String filename = UUID.randomUUID() + "_" + req.getAttachment().getOriginalFilename();
                String s3Key    = s3Service.uploadFile(req.getAttachment(), "images/" + filename);
                imageUrl        = s3Service.generatePresignedUrl(s3Key);
                log.info("Image uploaded to S3: key={}", s3Key);
            }
        }

        if (textInput.isEmpty() && voiceInput.isEmpty() && fileInput.isEmpty())
            throw new IllegalArgumentException("No input provided.");

        String finalInstruction = Stream.of(textInput, voiceInput, fileInput)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n\n"))
                .trim();

        log.info("FINAL INPUT:\n{}", finalInstruction);

        String rawInstructionForStorage = !textInput.isEmpty() ? textInput
                : !voiceInput.isEmpty() ? voiceInput
                : "(file input only)";

        String platformLabel = resolvePlatformLabel(req.getPlatform(), req.getCustomPlatformName());

        String cleanedIdeaJson = reasonInstruction(finalInstruction, platformLabel);
        boolean sameTopicGroup = extractSameTopicGroup(cleanedIdeaJson);
        log.info("Reasoning complete — sameTopicGroup={}", sameTopicGroup);

        String ragContext = retrieveWithFallback(cleanedIdeaJson, 5, platformLabel);

        String generatedJson = sameTopicGroup
                ? generateGroupedContent(cleanedIdeaJson, platformLabel, ragContext)
                : generateSeparateContent(cleanedIdeaJson, platformLabel, ragContext);

        String savedImageUrl = imageUrl;
        boolean wantsImage = imageUrl == null && isImageRequested(finalInstruction);
        if (wantsImage) {
            try {
                log.info("Generating AI image");
                String dallePrompt = aiService.buildImagePrompt(generatedJson, platformLabel);
                savedImageUrl = aiService.generateImage(dallePrompt);
                log.info("Generated image saved: {}", savedImageUrl);
            } catch (Exception ex) {
                log.warn("Image generation failed: {}", ex.getMessage());
            }
        }

        String parsedTitle = null, parsedSummary = null;
        String parsedIntro = null, parsedBody = null, parsedClosing = null;
        try {
            String cleanJson = generatedJson
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "").trim();
            java.util.Map<String, Object> parsed = objectMapper.readValue(cleanJson, java.util.Map.class);
            parsedTitle   = (String) parsed.get("title");
            parsedSummary = (String) parsed.get("summary");
            parsedIntro   = (String) parsed.get("intro");
            parsedBody    = (String) parsed.get("body");
            parsedClosing = (String) parsed.get("closing");
        } catch (Exception ex) {
            log.warn("Could not parse structured fields: {}", ex.getMessage());
        }

        ContentItem item = ContentItem.builder()
                .contentId(UUID.randomUUID().toString())
                .rawInstruction(rawInstructionForStorage)
                .extractedInputs(finalInstruction)
                .platform(req.getPlatform())
                .customPlatformName(req.getCustomPlatformName())
                .generatedContent(generatedJson)
                .title(parsedTitle)
                .summary(parsedSummary)
                .intro(parsedIntro)
                .body(parsedBody)
                .closing(parsedClosing)
                .imageUrl(savedImageUrl)
                .imageRequested(wantsImage)
                .isGrouped(sameTopicGroup)
                .status(ContentStatus.PENDING)
                .build();

        contentItemRepository.save(item);
        log.info("Saved content: {}", item.getContentId());
        return item;
    }

    @Transactional
    public ContentItem processApproval(ContentApprovalRequest req)
            throws JsonProcessingException {

        ContentItem item = findByContentId(req.getContentId());

        if (Boolean.TRUE.equals(req.getApproved())) {
            if (req.getEditedContent() != null && !req.getEditedContent().isBlank())
                item.setEditedContent(req.getEditedContent());
            if (req.getFeedback() != null)
                item.setAdminFeedback(req.getFeedback());

            String contentToStore = (req.getEditedContent() != null
                    && !req.getEditedContent().isBlank())
                    ? req.getEditedContent()
                    : item.getGeneratedContent();

            String platform = item.getPlatform() != null
                    ? item.getPlatform().name() : "GENERAL";

            ingestionService.storeContent(item.getContentId(), contentToStore, platform);
            item.setStatus(ContentStatus.APPROVED);
        } else {
            item.setStatus(ContentStatus.REJECTED);
            item.setAdminFeedback(req.getFeedback());
        }

        return contentItemRepository.save(item);
    }

    public ContentItem getByContentId(String contentId) {
        return findByContentId(contentId);
    }

    public List<ContentItem> getPending() {
        return contentItemRepository.findByStatus(ContentStatus.PENDING);
    }

    public List<ContentItem> getApproved() {
        return contentItemRepository.findByStatus(ContentStatus.APPROVED);
    }

    private String reasonInstruction(String rawInstruction, String platformLabel) {
        String user = """
                BUSINESS PLATFORM: %s

                CEO RAW INPUT (may contain spelling mistakes from voice or typing):
                %s
                """.formatted(platformLabel, rawInstruction);
        return cleanJson(aiService.chat(REASONING_SYSTEM, user));
    }

    private boolean extractSameTopicGroup(String reasoningJson) {
        try {
            return objectMapper.readTree(cleanJson(reasoningJson))
                    .path("sameTopicGroup").asBoolean(false);
        } catch (Exception ex) {
            return false;
        }
    }

    private String retrieveWithFallback(String query, int topK, String platformLabel) {
        List<Document> combined = new ArrayList<>();

        if (platformLabel != null && !platformLabel.isBlank()) {
            try {
                List<Document> scoped = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(topK / 2 + 1)
                                .filterExpression("clientName == '" + platformLabel + "'")
                                .build());
                combined.addAll(scoped);
                log.info("Content RAG Pass-1 (platform={}) → {} docs", platformLabel, scoped.size());
            } catch (Exception ex) {
                log.warn("Content RAG Pass-1 failed: {}", ex.getMessage());
            }
        }

        try {
            List<Document> broad = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(topK).build());
            combined.addAll(broad);
            log.info("Content RAG Pass-2 (broad) → {} docs", broad.size());
        } catch (Exception ex) {
            log.warn("Content RAG Pass-2 failed: {}", ex.getMessage());
        }

        return combined.stream()
                .collect(Collectors.toMap(
                        d -> d.getId() != null ? d.getId() : d.getText(),
                        d -> d,
                        (existing, replacement) -> existing))
                .values().stream()
                .limit(topK)
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
    }

    private String generateGroupedContent(String cleanedIdeaJson, String platformLabel, String ragContext) {
        String user = """
                PLATFORM: %s

                CEO IDEA (spelling already corrected):
                %s

                KNOWLEDGE BASE CONTEXT:
                %s
                """.formatted(platformLabel, cleanedIdeaJson, ragContext);
        return cleanJson(aiService.chat(GENERATION_SYSTEM_GROUPED, user));
    }

    private String generateSeparateContent(String cleanedIdeaJson, String platformLabel, String ragContext) {
        String user = """
                PLATFORM: %s

                CEO IDEA (spelling already corrected):
                %s

                KNOWLEDGE BASE CONTEXT:
                %s
                """.formatted(platformLabel, cleanedIdeaJson, ragContext);
        return cleanJson(aiService.chat(GENERATION_SYSTEM_SEPARATE, user));
    }

    private String smartSummarizeFile(MultipartFile file) {
        String rawText = extractAttachment(file);
        if (rawText.isBlank()) return "";
        return aiService.chat(
                "Extract key business facts only. Fix any spelling or grammar mistakes.",
                rawText.substring(0, Math.min(rawText.length(), 5000)));
    }

    private String extractAttachment(MultipartFile file) {
        try {
            if (file.getOriginalFilename() == null) return "";
            String name = file.getOriginalFilename().toLowerCase();
            String extracted = "";
            if (name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".txt"))
                extracted = documentService.extractText(file);
            else if (name.endsWith(".png") || name.endsWith(".jpg")
                    || name.endsWith(".jpeg") || name.endsWith(".webp"))
                extracted = aiService.extractImageText(file);
            else if (name.endsWith(".mp3") || name.endsWith(".wav")
                    || name.endsWith(".m4a") || name.endsWith(".aac"))
                extracted = aiService.transcribe(file);
            return extracted.replaceAll("\\s+", " ")
                    .replaceAll("[^\\x20-\\x7E\\n]", "").trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private ContentItem findByContentId(String contentId) {
        return contentItemRepository.findByContentId(contentId)
                .orElseThrow(() -> new RuntimeException("Content not found: " + contentId));
    }

    private String resolvePlatformLabel(PlatformType platform, String customPlatformName) {
        if (platform == PlatformType.OTHER && customPlatformName != null
                && !customPlatformName.isBlank())
            return customPlatformName.trim();
        return platform != null ? platform.name() : "ASK_OXY_AI";
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("\r\n", "\n").replaceAll("\r", "\n")
                .replaceAll("[ \t]+", " ").replaceAll("\n{3,}", "\n\n").trim();
    }

    private boolean isImageRequested(String instruction) {
        if (instruction == null) return false;
        String lower = instruction.toLowerCase();
        return lower.contains("with image") || lower.contains("generate image")
                || lower.contains("create image") || lower.contains("add image")
                || lower.contains("include image") || lower.contains("image post")
                || lower.contains("with picture") || lower.contains("with photo");
    }

    private String cleanJson(String s) {
        return s.replaceAll("```json", "").replaceAll("```", "").trim();
    }
}