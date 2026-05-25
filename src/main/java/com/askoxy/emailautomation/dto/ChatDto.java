package com.askoxy.emailautomation.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatDto {
    private String message;
    private String platform;   // optional: filter RAG by platform
    private String sessionId;
    private String reply;
    private Integer sourcesUsed;
}
