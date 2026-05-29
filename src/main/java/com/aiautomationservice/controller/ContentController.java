package com.aiautomationservice.controller;

import com.aiautomationservice.dto.*;
import com.aiautomationservice.service.SocialMediaService;
import com.aiautomationservice.service.ContentFormatterService;
import com.aiautomationservice.entity.ContentItem;
import com.aiautomationservice.entity.PaperclipItem;
import com.aiautomationservice.entity.VideoContent;
import com.aiautomationservice.enums.PlatformType;
import com.aiautomationservice.service.ContentService;
import com.aiautomationservice.service.IngestionService;
import com.aiautomationservice.service.VideoService;
import com.aiautomationservice.repository.PaperclipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ContentController {

    private final ContentService contentService;
    private final IngestionService ingestionService;
    private final ContentFormatterService formatterService;
    private final VideoService videoService;
    private final PaperclipRepository paperclipRepository;
    private final SocialMediaService socialMediaService;

    // ── COMPANY UPLOAD ────────────────────────────────────────────────────────

    @PostMapping(value = "/upload/company", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<UploadResponse>> uploadCompanyKnowledge(
            @RequestPart("file") MultipartFile[] files,
            @RequestParam PlatformType platformType,
            @RequestParam(required = false) String description) {

        List<UploadResponse> responses = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.getSize() > 500 * 1024 * 1024) {
                throw new RuntimeException(
                        "Company upload too large. Max allowed size is 500MB.");
            }
            responses.add(ingestionService.upload(file, platformType, description));
        }

        return ApiResponse.success(responses);
    }


    // ── CONTENT SUBMIT ────────────────────────────────────────────────────────

    @PostMapping(value = "/content/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ContentItem> submitInstruction(
            @RequestParam(required = false) String rawInstruction,
            @RequestParam(required = false) PlatformType platform,
            @RequestParam(required = false) String customPlatformName,
            @RequestPart(required = false) MultipartFile voiceFile,
            @RequestPart(required = false) MultipartFile attachment)
            throws Exception {

        if ((rawInstruction == null || rawInstruction.isBlank())
                && voiceFile == null && attachment == null)
            throw new RuntimeException("Please provide text, voice, or attachment.");

        if (attachment != null && attachment.getSize() > 100 * 1024 * 1024)
            throw new RuntimeException("Attachment too large. Max allowed size is 100MB.");

        return ApiResponse.success(
                contentService.submit(
                        ContentDto.builder()
                                .rawInstruction(rawInstruction)
                                .platform(platform)
                                .customPlatformName(customPlatformName)
                                .voiceFile(voiceFile)
                                .attachment(attachment)
                                .build()
                )
        );
    }

    // ── CONTENT APPROVALS ─────────────────────────────────────────────────────

    @GetMapping("/content/pending")
    public ApiResponse<List<ContentItem>> getPendingApprovals() {
        return ApiResponse.success(contentService.getPending());
    }

    @GetMapping("/content/approved")
    public ApiResponse<?> getApprovedContent(
            @RequestParam(required = false) String entityType) {

        if ("VIDEO".equalsIgnoreCase(entityType))
            return ApiResponse.success(videoService.getApproved());
        if ("PAPERCLIP".equalsIgnoreCase(entityType))
            return ApiResponse.success(contentService.getApprovedPaperclips());
        if ("CONTENT".equalsIgnoreCase(entityType))
            return ApiResponse.success(contentService.getApproved());
        return ApiResponse.success(contentService.getAllApproved());
    }

    @GetMapping("/content/{contentId}")
    public ApiResponse<ContentItem> getById(@PathVariable String contentId) {
        return ApiResponse.success(contentService.getByContentId(contentId));
    }

    // ── FORMAT — VIDEO | CONTENT | PAPERCLIP ─────────────────────────────────
    //
    //  Flow:  approve  →  POST /social/format  →  POST /social/publish
    //
    //  Entity   media sent to n8n
    //  ───────  ─────────────────────────────────────────────
    //  VIDEO    videoUrl (storagePath) + imageUrl (thumbnail)
    //  CONTENT  imageUrl
    //  PAPERCLIP paperclipUrl (s3FileUrl)

    @PostMapping("/social/format")
    public ApiResponse<FormatDto> format(@RequestBody FormatDto request) {

        String content;
        String imageUrl     = null;
        String videoUrl     = null;
        String paperclipUrl = null;

        if ("VIDEO".equalsIgnoreCase(request.getEntityType())) {
            VideoContent video = videoService.findById(request.getEntityId());
            content     = video.getApprovedContent() != null
                    ? video.getApprovedContent()
                    : video.getReasonedContent();
            imageUrl    = video.getThumbnailUrl();
            videoUrl    = video.getStoragePath();

        } else if ("PAPERCLIP".equalsIgnoreCase(request.getEntityType())) {
            PaperclipItem clip = paperclipRepository
                    .findByPaperclipId(request.getEntityId())
                    .orElseThrow(() -> new RuntimeException(
                            "Paperclip not found: " + request.getEntityId()));
            content      = clip.getExtractedText();
            paperclipUrl = clip.getS3FileUrl();

        } else {
            // CONTENT (default)
            ContentItem item = contentService.getByContentId(request.getEntityId());
            content  = item.getEditedContent() != null
                    ? item.getEditedContent()
                    : item.getGeneratedContent();
            imageUrl = item.getImageUrl();
        }

        FormatDto result = formatterService.format(
                content, imageUrl, videoUrl, paperclipUrl, request.getPlatforms());

        result.setEntityId(request.getEntityId());
        result.setEntityType(request.getEntityType());
        result.setPlatforms(request.getPlatforms());
        return ApiResponse.success(result);
    }

    // ── VIDEO SUBMIT ──────────────────────────────────────────────────────────

    @PostMapping(value = "/video/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<VideoContent> videoSubmit(
            @RequestPart("videoFile") MultipartFile videoFile) throws Exception {
        if (videoFile.isEmpty())
            throw new RuntimeException("Please upload a video file.");
        if (videoFile.getContentType() == null || !videoFile.getContentType().startsWith("video/"))
            throw new RuntimeException("Please upload a valid video file (mp4, mov, avi).");
        return ApiResponse.success(videoService.submit(videoFile));
    }

    @GetMapping("/video/all")
    public ApiResponse<List<VideoContent>> getAllVideos() {
        return ApiResponse.success(videoService.getUploaded());
    }

    // ── SHARED ACTIONS ────────────────────────────────────────────────────────

    @PostMapping("/add-to-clone")
    public ApiResponse<Object> addToClone(@RequestBody CloneApprovalRequest request) {
        return ApiResponse.success(videoService.addToClone(request));
    }

    // ── BLOG ──────────────────────────────────────────────────────────────────

    @PostMapping("/blog/format")
    public ApiResponse<BlogFormatDto> formatForBlog(@RequestBody BlogFormatDto request) {
        return ApiResponse.success(
                videoService.formatForBlog(
                        request.getEntityId(),
                        request.getEntityType(),
                        request.isGenerateImage()
                )
        );
    }

    @PostMapping("/image/{id}")
    public ApiResponse<BlogFormatDto> generateImage(
            @PathVariable String id,
            @RequestParam String entityType) {
        return ApiResponse.success(videoService.generateBlogImage(id, entityType));
    }

    @PostMapping("/blog/publish")
    public ApiResponse<BlogFormatDto> publishBlog(@RequestBody BlogFormatDto blogResult) {
        return ApiResponse.success(videoService.publishBlog(blogResult));
    }

    // ── SOCIAL PUBLISH ────────────────────────────────────────────────────────
    // Single publish endpoint for VIDEO, CONTENT, and PAPERCLIP.
    // Send the formattedContent map returned by /social/format as approvedBodies.


    @PostMapping("/social/publish")
    public ApiResponse<Map<String, String>> publishToSocial(
            @RequestBody FormatDto request) {

        Map<String, String> results =
                socialMediaService.postToAllPlatforms(
                        request.getFormattedContent()
                );

        return ApiResponse.success(results);
    }

    // ── PAPERCLIP ─────────────────────────────────────────────────────────────

    @PostMapping(value = "/paperclip/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PaperclipResponse> analyzePaperclip(
            @RequestPart("files") MultipartFile[] files) {
        return ApiResponse.success(contentService.analyzePaperclip(Arrays.asList(files)));
    }

    @GetMapping("/paperclip/all")
    public ApiResponse<List<PaperclipResponse>> getAllPaperclips() {
        return ApiResponse.success(contentService.getAllPaperclips());
    }
}
