package com.askoxy.emailautomation.dto;

import com.askoxy.emailautomation.enums.PlatformType;
import lombok.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentDto {
    private String rawInstruction;
    private PlatformType platform;
    private String customPlatformName; // filled only when platform = OTHER
    private MultipartFile voiceFile;
    private MultipartFile attachment;
    private String title;
    private String body;
    private String hashtags;
    private String callToAction;
    private String intro;
    private List<ContentSection> sections;
    private String closing;
    private Boolean isGrouped;
    private String summary;
}
