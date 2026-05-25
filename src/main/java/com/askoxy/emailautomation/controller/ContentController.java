package com.askoxy.emailautomation.controller;

import com.askoxy.emailautomation.service.ContentFormatterService;
import com.askoxy.emailautomation.dto.*;
import com.askoxy.emailautomation.entity.ContentItem;
import com.askoxy.emailautomation.entity.VideoContent;
import com.askoxy.emailautomation.enums.PlatformType;
import com.askoxy.emailautomation.service.ContentService;
import com.askoxy.emailautomation.service.IngestionService;
import com.askoxy.emailautomation.service.VideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ContentController {

    private final ContentService contentService;
    private final IngestionService ingestionService;
    private final ContentFormatterService formatterService;
    private final VideoService videoService;

    // ── COMPANY UPLOAD ────────────────────────────────────────────────────────

    @PostMapping(value = "/upload/company", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResponse> uploadCompanyKnowledge(
            @RequestParam("file") MultipartFile file,
            @RequestParam PlatformType platformType,
            @RequestParam(required = false) String description) {

        if (file.getSize() > 200 * 1024 * 1024)
            throw new RuntimeException("Company upload too large. Max allowed size is 200MB.");
        return ApiResponse.success(ingestionService.upload(file, platformType, description));
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

        if (attachment != null && attachment.getSize() > 10 * 1024 * 1024)
            throw new RuntimeException("Attachment too large. Max allowed size is 10MB.");

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
    public ApiResponse<List<ContentItem>> getApprovedContent() {
        return ApiResponse.success(contentService.getApproved());
    }

    @GetMapping("/content/{contentId}")
    public ApiResponse<ContentItem> getById(@PathVariable String contentId) {
        return ApiResponse.success(contentService.getByContentId(contentId));
    }

    // ── FORMAT — handles both VIDEO and CONTENT ───────────────────────────────

    @PostMapping("social/format")
    public ApiResponse<FormatDto> format(@RequestBody FormatDto request) {

        String content;
        String imageUrl = null;
        String videoUrl = null;

        if ("VIDEO".equalsIgnoreCase(request.getEntityType())) {
            VideoContent video = videoService.findById(request.getEntityId());
            content  = video.getApprovedContent() != null ? video.getApprovedContent() : video.getReasonedContent();
            imageUrl = video.getThumbnailUrl();
            videoUrl = video.getStoragePath();
        } else {
            ContentItem item = contentService.getByContentId(request.getEntityId());
            content  = item.getEditedContent() != null ? item.getEditedContent() : item.getGeneratedContent();
            imageUrl = item.getImageUrl();
        }

        FormatDto formattedResult = formatterService.format(content, imageUrl, videoUrl, request.getPlatforms());
        formattedResult.setEntityId(request.getEntityId());
        formattedResult.setEntityType(request.getEntityType());
        formattedResult.setPlatforms(request.getPlatforms());
        formattedResult.setFormats(formattedResult.getFormattedContent());
        return ApiResponse.success(formattedResult);
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
        return ApiResponse.success(videoService.getAll());
    }

    // ── SHARED ACTIONS (video + content both use these) ───────────────────────

    @PostMapping("/add-to-clone")
    public ApiResponse<Object> addToClone(@RequestBody CloneApprovalRequest request) {
        return ApiResponse.success(videoService.addToClone(request));
    }

    @PostMapping("/social/post")
    public ApiResponse<SocialPostDto> postToSocial(
            @RequestBody SocialPostDto request) {
        return ApiResponse.success(videoService.postToSocial(request));
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

        return ApiResponse.success(
                videoService.generateBlogImage(id, entityType)
        );
    }

    @PostMapping("/blog/publish")
    public ApiResponse<BlogFormatDto> publishBlog(@RequestBody BlogFormatDto blogResult) {
        return ApiResponse.success(videoService.publishBlog(blogResult));
    }
}