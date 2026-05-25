package com.askoxy.emailautomation.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ContentSection {
    private String heading;
    private String body;
}
