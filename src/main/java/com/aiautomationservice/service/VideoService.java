package com.aiautomationservice.service;


import com.aiautomationservice.dto.*;
import com.aiautomationservice.entity.PaperclipItem;
import com.aiautomationservice.repository.PaperclipRepository;
import com.aiautomationservice.entity.VideoContent;
import com.aiautomationservice.enums.ContentStatus;
import com.aiautomationservice.repository.VideoContentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.Async;
import java.util.*;

import com.aiautomationservice.repository.ContentItemRepository;
import com.aiautomationservice.entity.ContentItem;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    private final BlogService            blogService;
    private final AIService              aiService;
    private final GeminiVideoService     geminiVideoService;
    private final IngestionService       ingestionService;
    private final SocialMediaService     socialMediaService;
    private final VideoContentRepository videoContentRepository;
    private final ObjectMapper           objectMapper;
    private final ContentItemRepository  contentItemRepository;
    private final PaperclipRepository    paperclipRepository;
    private final S3Service              s3Service;

    // Files are stored in S3 bucket 'radha-clone' under prefix 'videos/'

    public VideoContent findById(String entityId) {
        return find(entityId);
    }

    @Transactional
    public VideoContent submit(MultipartFile videoFile) throws Exception {

        // Save video to S3 — fast
        String storagePath = saveFile(videoFile);

        // Save a stub to DB immediately with PROCESSING status
        VideoContent content = VideoContent.builder()
                .videoId(UUID.randomUUID().toString())
                .originalFileName(videoFile.getOriginalFilename())
                .storagePath(storagePath)
                .fileSizeBytes(videoFile.getSize())
                .status(ContentStatus.PROCESSING)
                .addedToClone(false)
                .blogPublished(false)
                .socialPosted(false)
                .build();

        videoContentRepository.save(content);
        log.info("Video saved: videoId={} — AI analysis starting in background", content.getVideoId());

        // Fire and forget — runs on radha-async- thread pool
        analyzeVideoAsync(content.getVideoId(), storagePath,
                videoFile.getOriginalFilename(), videoFile.getContentType());

        return content; // responds in ~5 seconds
    }

    @Async
    public void analyzeVideoAsync(String videoId, String s3Key,
                                  String originalName, String contentType) {
        try {
            byte[] videoBytes = s3Service.downloadBytes(s3Key);
            MockMultipartFile file = new MockMultipartFile(
                    "videoFile", originalName, contentType, videoBytes);

            VideoAnalysisResult analysis = geminiVideoService.analyzeVideo(file);

            VideoContent content = videoContentRepository.findByVideoId(videoId)
                    .orElseThrow(() -> new RuntimeException("Video not found: " + videoId));

            content.setAudioTranscript(analysis.getAudioTranscript());
            content.setVisualContent(analysis.getVisualContent());
            content.setReasoningNotes(analysis.getReasoningNotes());
            content.setReasonedContent(analysis.getReasonedContent());
            content.setTitle(analysis.getTitle());
            content.setSummary(analysis.getSummary());
            content.setIntro(analysis.getIntro());
            content.setBody(analysis.getBody());
            content.setClosing(analysis.getClosing());
            content.setApprovedContent(
                    analysis.getReasonedContent() != null
                            ? analysis.getReasonedContent()
                            : analysis.getDraftPost());
            content.setStatus(ContentStatus.PENDING);
            videoContentRepository.save(content);

            log.info("Video analysis complete: videoId={}", videoId);

        } catch (Exception e) {
            log.error("Video analysis FAILED: videoId={}", videoId, e);
            videoContentRepository.findByVideoId(videoId).ifPresent(c -> {
                c.setStatus(ContentStatus.FAILED);
                videoContentRepository.save(c);
            });
        }
    }

    @Transactional
    public Object addToClone(CloneApprovalRequest req) {

        if (!Boolean.TRUE.equals(req.getConfirmed()))
            throw new RuntimeException("Confirmation required.");

        if ("CONTENT".equalsIgnoreCase(req.getEntityType())) {

            ContentItem item = contentItemRepository.findByContentId(req.getEntityId())
                    .orElseThrow(() -> new RuntimeException("Content not found: " + req.getEntityId()));

            String text = (req.getEditedContent() != null && !req.getEditedContent().isBlank())
                    ? req.getEditedContent() : item.getGeneratedContent();

            ingestionService.storeContent(item.getContentId(), text, "CONTENT");
            item.setEditedContent(text);
            item.setStatus(ContentStatus.APPROVED);
            return contentItemRepository.save(item);

        } else if ("PAPERCLIP".equalsIgnoreCase(req.getEntityType())) {

            PaperclipItem pc = paperclipRepository.findByPaperclipId(req.getEntityId())
                    .orElseThrow(() -> new RuntimeException("Paperclip not found: " + req.getEntityId()));

            PaperclipAnalysisResult analysis;
            try { analysis = objectMapper.readValue(pc.getAnalysisJson(), PaperclipAnalysisResult.class); }
            catch (Exception ex) { throw new RuntimeException("Failed to parse paperclip"); }

            String text = (req.getEditedContent() != null && !req.getEditedContent().isBlank())
                    ? req.getEditedContent()
                    : (analysis.getSummary() != null ? analysis.getSummary().getDetailedSummary() : pc.getExtractedText());

            ingestionService.storeContent(pc.getPaperclipId(), text, "PAPERCLIP");
            pc.setAddedToClone(true);
            paperclipRepository.save(pc);
            return pc;
        } else {

            /*
             * =========================================================
             * VIDEO FLOW
             * =========================================================
             */

            VideoContent content = videoContentRepository.findByVideoId(req.getEntityId())
                    .orElseThrow(() -> new RuntimeException("Video not found: " + req.getEntityId()));

            String text = (req.getEditedContent() != null && !req.getEditedContent().isBlank())
                    ? req.getEditedContent() : content.getApprovedContent();

            String vectorStoreId = ingestionService.storeContent(content.getVideoId(), text, "VIDEO_CONTENT");
            content.setApprovedContent(text);
            content.setVectorStoreId(vectorStoreId);
            content.setAddedToClone(true);
            updateStatus(content);
            return videoContentRepository.save(content);
        }
    }

    @Transactional
    public FormatDto formatForSocial(FormatDto req) {

        VideoContent content = find(req.getEntityId());

        String textToFormat = (req.getEditedContent() != null && !req.getEditedContent().isBlank())
                ? req.getEditedContent()
                : content.getApprovedContent();

        Map<String, PlatformContent> formatted =
                socialMediaService.formatForPlatforms(
                        textToFormat,
                        req.getPlatforms(),
                        content.getThumbnailUrl(),
                        content.getStoragePath()
                );

        try {
            content.setSocialFormats(objectMapper.writeValueAsString(formatted));
        } catch (Exception ignored) {}

        videoContentRepository.save(content);

        return FormatDto.builder()
                .entityId(content.getVideoId())
                .contentId(content.getVideoId())
                .formats(formatted)
                .formattedContent(formatted)
                .build();
    }

    @Transactional
    public SocialPostDto postToSocial(SocialPostDto req) {

        VideoContent content = find(req.getEntityId());
        Map<String, String> results = socialMediaService.postToAllPlatforms(req.getApprovedBodies());

        try { content.setSocialPostResults(objectMapper.writeValueAsString(results)); } catch (Exception ignored) {}
        content.setSocialPosted(true);
        updateStatus(content);
        videoContentRepository.save(content);

        return SocialPostDto.builder()
                .entityId(req.getEntityId())
                .results(results)
                .build();
    }

    @Transactional
    public BlogFormatDto formatForBlog(
            String entityId,
            String entityType,
            boolean generateImage) {

        String contentText;
        String imageUrl = null;
        String storagePath = null;

        String blogTitle   = "";
        String blogBody    = "";
        String blogClosing = "";
        String blogSummary = "";

        /*
         * =========================================================
         * CONTENT FLOW
         * =========================================================
         */

        if ("CONTENT".equalsIgnoreCase(entityType)) {

            ContentItem item = contentItemRepository.findByContentId(entityId)
                    .orElseThrow(() ->
                            new RuntimeException("Content not found: " + entityId));

            contentText =
                    item.getEditedContent() != null
                            ? item.getEditedContent()
                            : item.getGeneratedContent();

            /*
             * CONTENT RULES
             *
             * generateImage = true
             *      -> return image
             *
             * generateImage = false
             *      -> no image
             */

            if (generateImage) {

                // Generate image only if not already present
                if (item.getImageUrl() == null
                        || item.getImageUrl().isBlank()) {

                    String imagePrompt =
                            aiService.buildImagePrompt(
                                    contentText,
                                    """
                                    Create a highly professional,
                                    cinematic,
                                    modern blog thumbnail.
    
                                    Requirements:
                                    - Strong visual storytelling
                                    - Relevant to article topic
                                    - Modern business / AI / startup style
                                    - Clean composition
                                    - Vibrant but professional colors
                                    - Ultra realistic
                                    - No watermarks
                                    - No text inside image
                                    - Social media quality
                                    - High engagement thumbnail
                                    - 16:9 aspect ratio
                                    - Premium blog cover style
                                    - Relevant to content meaning
                                    - Eye-catching visual
                                    """);

                    String s3Key =
                            aiService.generateImage(
                                    imagePrompt);

                    item.setImageUrl(s3Key);

                    contentItemRepository.save(item);
                }

                imageUrl =
                        item.getImageUrl() != null
                                ? s3Service.resolveUrl(
                                item.getImageUrl())
                                : null;

            } else {

                // CONTENT WITHOUT IMAGE
                imageUrl = null;
            }

        } else if ("PAPERCLIP".equalsIgnoreCase(entityType)) {

            PaperclipItem pc = paperclipRepository.findByPaperclipId(entityId)
                    .orElseThrow(() -> new RuntimeException("Paperclip not found: " + entityId));
            try {
                PaperclipAnalysisResult analysis = objectMapper.readValue(pc.getAnalysisJson(), PaperclipAnalysisResult.class);
                contentText  = analysis.getSummary() != null ? analysis.getSummary().getDetailedSummary() : pc.getExtractedText();
                blogTitle    = analysis.getSummary() != null ? analysis.getSummary().getShortSummary()     : "";
                blogSummary  = contentText;
                blogBody     = "";
                blogClosing  = "";
            } catch (Exception ex) { contentText = pc.getExtractedText(); blogTitle = ""; blogSummary = ""; blogBody = ""; blogClosing = ""; }

            if (generateImage) {
                if (pc.getImageUrl() == null || pc.getImageUrl().isBlank()) {
                    String s3Key = aiService.generateImage(aiService.buildImagePrompt(contentText,
                            "Professional blog thumbnail, modern business style, no text, 16:9"));
                    pc.setImageUrl(s3Key);          // ✅ already saves to entity
                    paperclipRepository.save(pc);   // ✅ already persists to DB
                }
                imageUrl = pc.getImageUrl() != null ? s3Service.resolveUrl(pc.getImageUrl()) : null;
            } else {
                // generateImage=false → reuse any previously generated image from DB
                if (pc.getS3FileUrl() != null
                        && !pc.getS3FileUrl().isBlank()) {

                    String firstKey =
                            pc.getS3FileUrl()
                                    .split(",")[0]
                                    .trim();

                    imageUrl =
                            s3Service.resolveUrl(firstKey);

                } else {

                    imageUrl = null;
                }                   // ✅ was always null before — now restores saved URL
            }
        } else {

            /*
             * =========================================================
             * VIDEO FLOW
             * =========================================================
             */

            VideoContent content = find(entityId);

            contentText =
                    content.getApprovedContent() != null
                            ? content.getApprovedContent()
                            : content.getReasonedContent();

            storagePath = content.getStoragePath();

            blogTitle =
                    content.getTitle() != null
                            ? content.getTitle()
                            : "";

            blogBody =
                    content.getBody() != null
                            ? content.getBody()
                            : "";

            blogClosing =
                    content.getClosing() != null
                            ? content.getClosing()
                            : "";

            blogSummary =
                    content.getSummary() != null
                            ? content.getSummary()
                            : "";

            if (generateImage) {

                /*
                 * IMAGE POST MODE
                 */

                if (content.getThumbnailUrl() == null
                        || content.getThumbnailUrl().isBlank()) {

                    generateImageForContent(
                            entityId,
                            contentText,
                            content);

                    content = find(entityId);
                }

                imageUrl =
                        content.getThumbnailUrl() != null
                                ? s3Service.resolveUrl(
                                content.getThumbnailUrl())
                                : null;

            } else {

                /*
                 * VIDEO POST MODE
                 */

                imageUrl = null;
            }
        }

        String fullContext =
                "APPROVED CONTENT:\n"
                        + contentText
                        + (blogTitle.isBlank()
                        ? ""
                        : "\n\nTITLE: " + blogTitle)
                        + (blogSummary.isBlank()
                        ? ""
                        : "\n\nSUMMARY: " + blogSummary)
                        + (blogBody.isBlank()
                        ? ""
                        : "\n\nBODY: " + blogBody)
                        + (blogClosing.isBlank()
                        ? ""
                        : "\n\nCLOSING: " + blogClosing);

        /*
         * VIDEO FILE RULE
         *
         * IMAGE MODE  -> no video
         * VIDEO MODE  -> send video
         */
        String videoFileUrl =
                generateImage
                        ? null
                        : resolveVideoFileUrl(storagePath);

        String rawJson = aiService.chat("""
            You are an expert blog writer for AskOxy. Return ONLY valid JSON. No markdown outside the JSON values.

            BLOG WRITING RULES:
            - Use the SOURCE CONTENT as input — extract key ideas and facts
            - Write FRESH, ORIGINAL content — do NOT copy source text word-for-word
            - Write naturally in first person as Radha Krishna Thatavarthi
            - SEO-friendly title (6-12 words, include main keyword)
            - Start with an engaging introduction paragraph
            - Write 2-3 main sections with bold headings using **Heading**
            - Use bullet points where listing features or benefits
            - End with a conclusion paragraph and clear call to action
            - 600-1000 words
            - Professional but warm, accessible tone
            - NO hashtags anywhere
            - Add META at the very end: META: [SEO description under 160 chars]
            - Zero spelling or grammar mistakes

            Source content:
            %s

            Return ONLY this JSON:
            {
              "title": "<compelling blog title — 6-12 words, SEO-friendly>",
              "description": "<full blog post with **bold headings**, bullet points, conclusion, META line at end>",
              "socialMediaCaptions": "<2-3 sentences to share this blog. No hashtags.>"
            }
            """.formatted(fullContext));

        BlogFormatDto result;

        try {

            String clean = rawJson
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            @SuppressWarnings("unchecked")
            Map<String, String> parsed =
                    objectMapper.readValue(clean, Map.class);

            result = BlogFormatDto.builder()
                    .entityId(entityId)
                    .entityType(entityType)
                    .generateImage(generateImage)
                    .title(parsed.getOrDefault(
                            "title",
                            "New Post by Radha"))
                    .description(parsed.getOrDefault(
                            "description",
                            contentText))
                    .socialMediaCaptions(parsed.getOrDefault(
                            "socialMediaCaptions",
                            ""))
                    .addedBy("Radha")
                    .videoUrl(storagePath)
                    .videoFileUrl(videoFileUrl)
                    .imageUrl(imageUrl)
                    .status(imageUrl != null
                            ? "IMAGE_READY"
                            : "DRAFT")
                    .build();

        } catch (Exception ex) {

            log.error(
                    "Blog AI parse failed for entityId={}",
                    entityId,
                    ex);

            result = BlogFormatDto.builder()
                    .entityId(entityId)
                    .entityType(entityType)
                    .generateImage(generateImage)
                    .title("New Post by Radha")
                    .description(contentText)
                    .socialMediaCaptions("")
                    .addedBy("Radha")
                    .videoUrl(storagePath)
                    .videoFileUrl(videoFileUrl)
                    .imageUrl(imageUrl)
                    .status(imageUrl != null
                            ? "IMAGE_READY"
                            : "DRAFT")
                    .build();
        }

        /*
         * SAVE BLOG FORMAT
         */

        if ("CONTENT".equalsIgnoreCase(entityType)) {
            ContentItem item = contentItemRepository.findByContentId(entityId).orElseThrow();
            try { item.setBlogFormat(objectMapper.writeValueAsString(result)); } catch (Exception ignored) {}
            contentItemRepository.save(item);
        } else if ("PAPERCLIP".equalsIgnoreCase(entityType)) {
            PaperclipItem pc = paperclipRepository.findByPaperclipId(entityId).orElseThrow();
            try { pc.setBlogFormat(objectMapper.writeValueAsString(result)); } catch (Exception ignored) {}
            paperclipRepository.save(pc);
        } else {
            VideoContent content = find(entityId);
            try { content.setBlogFormat(objectMapper.writeValueAsString(result)); } catch (Exception ignored) {}
            videoContentRepository.save(content);
        }
        return result;
    }
    @Transactional
    public BlogFormatDto generateBlogImage(String entityId, String entityType) {

        String contentText;
        String imageUrl;
        String storagePath = null;

        if ("CONTENT".equalsIgnoreCase(entityType)) {

            ContentItem item = contentItemRepository.findByContentId(entityId)
                    .orElseThrow(() ->
                            new RuntimeException("Content not found: " + entityId));

            contentText = item.getEditedContent() != null
                    ? item.getEditedContent()
                    : item.getGeneratedContent();

            try {
                String imagePrompt = aiService.buildImagePrompt(contentText, "BLOG");
                // s3Key holds the raw S3 key (e.g. "images/uuid.png") — stored in DB for future resolution
                String s3Key = aiService.generateImage(imagePrompt);

                item.setImageUrl(s3Key);

                // Resolve to a full presigned URL for the API response
                String presignedImageUrl = s3Service.resolveUrl(s3Key);

                BlogFormatDto result = BlogFormatDto.builder()
                        .entityId(entityId)
                        .entityType("CONTENT")
                        .generateImage(true)
                        .imageUrl(presignedImageUrl)
                        .addedBy("Radha")
                        .status("IMAGE_READY")
                        .build();

                try {
                    item.setBlogFormat(objectMapper.writeValueAsString(result));
                } catch (Exception ignored) {}

                contentItemRepository.save(item);

                return result;

            } catch (Exception ex) {
                log.error("BLOG IMAGE FAILED", ex);
                throw new RuntimeException("Image generation failed");
            }

        } else if ("PAPERCLIP".equalsIgnoreCase(entityType)) {

            PaperclipItem pc = paperclipRepository.findByPaperclipId(entityId)
                    .orElseThrow(() -> new RuntimeException("Paperclip not found: " + entityId));
            try {
                PaperclipAnalysisResult analysis = objectMapper.readValue(pc.getAnalysisJson(), PaperclipAnalysisResult.class);
                contentText = analysis.getSummary() != null ? analysis.getSummary().getDetailedSummary() : pc.getExtractedText();
            } catch (Exception ex) { contentText = pc.getExtractedText(); }

            try {
                String s3Key = aiService.generateImage(aiService.buildImagePrompt(contentText, "BLOG"));
                pc.setImageUrl(s3Key);
                String presignedUrl = s3Service.resolveUrl(s3Key);
                BlogFormatDto result = BlogFormatDto.builder()
                        .entityId(entityId).entityType("PAPERCLIP")
                        .generateImage(true)
                        .imageUrl(presignedUrl).addedBy("Radha").status("IMAGE_READY").build();
                try { pc.setBlogFormat(objectMapper.writeValueAsString(result)); } catch (Exception ignored) {}
                paperclipRepository.save(pc);
                return result;
            } catch (Exception ex) {
                log.error("Paperclip image generation failed", ex);
                throw new RuntimeException("Image generation failed");
            }
        } else {

            VideoContent content = find(entityId);

            contentText = content.getApprovedContent() != null
                    ? content.getApprovedContent()
                    : content.getReasonedContent();

            // s3ImageKey is the raw S3 key stored in DB; resolve to presigned URL for response
            String s3ImageKey = generateImageForContent(entityId, contentText, content);

            storagePath = content.getStoragePath();

            BlogFormatDto result = BlogFormatDto.builder()
                    .entityId(entityId)
                    .generateImage(true)
                    .entityType("VIDEO")
                    .imageUrl(s3Service.resolveUrl(s3ImageKey))
                    .videoUrl(storagePath)
                    .videoFileUrl(resolveVideoFileUrl(storagePath))
                    .addedBy("Radha")
                    .status("IMAGE_READY")
                    .build();

            try {
                content.setBlogFormat(objectMapper.writeValueAsString(result));
            } catch (Exception ignored) {}

            videoContentRepository.save(content);

            return result;
        }
    }

    @Transactional
    public BlogFormatDto publishBlog(BlogFormatDto blogResult) {

        String imageUrlToUse = blogResult.getImageUrl() != null ? blogResult.getImageUrl() : "";

        String videoFileUrlToUse;
        if ("CONTENT".equalsIgnoreCase(blogResult.getEntityType())) {
            videoFileUrlToUse = blogResult.getVideoFileUrl() != null ? blogResult.getVideoFileUrl() : "";

        } else if ("PAPERCLIP".equalsIgnoreCase(blogResult.getEntityType())) {
            videoFileUrlToUse = "";
        } else {
            VideoContent content = find(blogResult.getEntityId());
            videoFileUrlToUse =
                    (blogResult.getVideoFileUrl() != null && !blogResult.getVideoFileUrl().isBlank())
                            ? blogResult.getVideoFileUrl()
                            : resolveVideoFileUrl(content.getStoragePath());
        }

        String result = blogService.post(
                blogResult.getTitle(),
                blogResult.getDescription(),
                imageUrlToUse,
                videoFileUrlToUse
        );

        if (result != null && result.startsWith("POSTED")) {
            blogResult.setStatus("PUBLISHED");
            blogResult.setBlogPostId("POSTED SUCCESSFULLY");
        } else {
            blogResult.setStatus("FAILED: " + result);
        }

        if ("CONTENT".equalsIgnoreCase(blogResult.getEntityType())) {
            ContentItem item = contentItemRepository.findByContentId(blogResult.getEntityId())
                    .orElseThrow(() -> new RuntimeException("Content not found: " + blogResult.getEntityId()));
            item.setBlogPublished(true);
            try { item.setBlogFormat(objectMapper.writeValueAsString(blogResult)); } catch (Exception ignored) {}
            contentItemRepository.save(item);

        } else if ("PAPERCLIP".equalsIgnoreCase(blogResult.getEntityType())) {
            PaperclipItem pc = paperclipRepository.findByPaperclipId(blogResult.getEntityId())
                    .orElseThrow(() -> new RuntimeException("Paperclip not found: " + blogResult.getEntityId()));
            pc.setBlogPublished(true);
            try { pc.setBlogFormat(objectMapper.writeValueAsString(blogResult)); } catch (Exception ignored) {}
            paperclipRepository.save(pc);
        } else {
            VideoContent content = find(blogResult.getEntityId());
            content.setBlogPostId(blogResult.getBlogPostId());
            content.setBlogPublished(true);
            updateStatus(content);
            try { content.setBlogFormat(objectMapper.writeValueAsString(blogResult)); } catch (Exception ignored) {}
            videoContentRepository.save(content);
        }

        return blogResult;
    }

    private String generateAndUploadImageToS3(String contentText)
            throws Exception {

        String imagePrompt =
                aiService.buildImagePrompt(
                        contentText,
                        "BLOG"
                );

        // AIService already uploads image to S3
        // and returns the S3 key
        return aiService.generateImage(imagePrompt);
    }

    public List<VideoContent> getAll() { return videoContentRepository.findAll(); }

    public List<VideoContent> getApproved() {
        return videoContentRepository.findByStatus(ContentStatus.APPROVED);
    }
    private String generateImageForContent(
            String id,
            String contentText,
            VideoContent content) {

        try {

            log.info(
                    "Generating image for {}",
                    id);

            String s3ImageKey =
                    generateAndUploadImageToS3(
                            contentText);

            content.setThumbnailUrl(
                    s3ImageKey);

            videoContentRepository
                    .save(content);

            log.info(
                    "Stored image in S3 {}",
                    s3ImageKey);

            return s3ImageKey;

        } catch (Exception ex) {

            log.error(
                    "Image generation failed",
                    ex);

            return null;
        }
    }
    /**
     * Resolves a video URL from its stored S3 key.
     * Generates a 7-day pre-signed URL for private bucket access.
     * Falls back gracefully for legacy records that stored a full URL.
     */
    private String resolveVideoFileUrl(String storagePath) {
        return s3Service.resolveUrl(storagePath);
    }

    private VideoContent find(String id) {
        return videoContentRepository.findByVideoId(id)
                .orElseThrow(() -> new RuntimeException("Video not found: " + id));
    }

    /**
     * Uploads the video to S3 (bucket: radha-clone, prefix: videos/).
     * @return the S3 key, e.g. "videos/uuid.mp4" — stored in VideoContent.storagePath
     */
    private String saveFile(MultipartFile file) throws Exception {
        String ext = file.getOriginalFilename() != null && file.getOriginalFilename().contains(".")
                ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".")) : ".mp4";
        String s3Key = "videos/" + UUID.randomUUID() + ext;
        return s3Service.uploadFile(file, s3Key);
    }

    private void updateStatus(VideoContent content) {
        if (content.getAddedToClone()
                || content.getBlogPublished()
                || content.getSocialPosted()) {
            content.setStatus(ContentStatus.APPROVED);
        }
    }

    public List<VideoContent> getUploaded() {
        return videoContentRepository.findByStatusNot(ContentStatus.FAILED);
    }

}