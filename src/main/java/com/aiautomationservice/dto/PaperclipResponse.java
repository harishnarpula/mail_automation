package com.aiautomationservice.dto;
import lombok.*;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaperclipResponse {
    private String paperclipId;
    private String fileName;         // ← ADD
    private String s3FileUrl;        // ← ADD
    private String uploadedAt;
    private String imageUrl;
    private List<String> imageUrls; // ALL files ← ADD THIS

    private PaperclipAnalysisResult analysis;
}
