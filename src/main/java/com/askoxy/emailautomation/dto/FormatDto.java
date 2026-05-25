package com.askoxy.emailautomation.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormatDto {
    private String entityId;
    private String entityType;      // "VIDEO" or "CONTENT"
    private List<String> platforms;
    private String editedContent;
    private String contentId;
    private Map<String, PlatformContent> formattedContent;    // key = "LINKEDIN", "FACEBOOK", etc.
    private Map<String, PlatformContent> formats;
}
