package com.askoxy.emailautomation.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishDto {
    private String contentId;
    private List<String> channels;
    private Map<String, PlatformContent> formattedContent;
    private boolean success;
    private Map<String, String> channelResults;
    private String message;
}
