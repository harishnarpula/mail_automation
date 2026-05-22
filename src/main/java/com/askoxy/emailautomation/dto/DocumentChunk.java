package com.askoxy.emailautomation.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentChunk {
    private int chunkIndex;
    private String chunkText;
    private int characterCount;
}