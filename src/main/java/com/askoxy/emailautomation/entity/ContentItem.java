package com.askoxy.emailautomation.entity;

import com.askoxy.emailautomation.enums.ContentStatus;
import com.askoxy.emailautomation.enums.PlatformType;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "content_items")
public class ContentItem extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String contentId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rawInstruction;

    @Column(columnDefinition = "TEXT")
    private String extractedInputs;

    @Enumerated(EnumType.STRING)
    @Column
    private PlatformType platform;

    @Column
    private String customPlatformName;

    @Column(columnDefinition = "TEXT")
    private String generatedContent;

    @Column(columnDefinition = "TEXT")
    private String editedContent;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String intro;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(columnDefinition = "TEXT")
    private String closing;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status;

    @Column(columnDefinition = "TEXT")
    private String adminFeedback;

    @Column(columnDefinition = "TEXT")
    private String publishResults;

    @Column(columnDefinition = "TEXT")
    private String selectedChannels;

    @Column(name = "is_grouped")
    private Boolean isGrouped = false;

    @Column(columnDefinition = "TEXT")
    private String imageUrl;

    @Column
    private Boolean imageRequested = false;

    @Column(columnDefinition = "TEXT")
    private String imageFileUrl;

    @Column(columnDefinition = "TEXT")
    private String videoUrl;

    @Column(columnDefinition = "TEXT")
    private String videoFileUrl;

    @Builder.Default
    @Column
    private Boolean blogPublished = false;

    @Column(columnDefinition = "TEXT")
    private String blogFormat;      // ← ADDED
}