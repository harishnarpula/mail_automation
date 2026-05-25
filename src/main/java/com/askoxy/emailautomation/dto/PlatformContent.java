package com.askoxy.emailautomation.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformContent {

    // platform name
    private String platform;

    // main generated content
    private String text;

    // structured title
    private String title;

    // optional subject (email/newsletter)
    private String subject;

    // hashtags separated
    private String hashtags; // platform specific hashtags
    // media
    private String imageUrl;
    private String videoUrl;

    // metadata
    private Integer charCount;
    private String limit;

    // AI + workflow metadata
    private String contentType; // BLOG / VIDEO / SOCIAL / EMAIL

    private String tone; // PROFESSIONAL / CASUAL / SALES

    private Map<String, Object> extra;
}