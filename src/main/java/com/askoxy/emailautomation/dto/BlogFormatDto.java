package com.askoxy.emailautomation.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlogFormatDto {
    private String entityId;
    private String entityType;      // "VIDEO" or "CONTENT"
    private boolean generateImage;
    private String title;
    private String description;
    private String socialMediaCaptions;
    private String addedBy;
    private String videoUrl;
    private String videoFileUrl;
    private String imageUrl;
    private String status;
    private String blogPostId;
}
