package com.askoxy.emailautomation.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PdfUploadResponse {
    private boolean success;
    private String message;
    private String fileId;
    private String fileName;
    private int totalChunks;
}