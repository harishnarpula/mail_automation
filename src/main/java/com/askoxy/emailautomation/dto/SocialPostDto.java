package com.askoxy.emailautomation.dto;

import lombok.*;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialPostDto {
    private String entityId;
    private Map<String, PlatformContent> approvedBodies;
    private Map<String, String> results;
}
