package com.askoxy.emailautomation.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UploadResponse {
    private boolean success;
    private String message;
    private String fileId;
    private String fileName;
    private Integer chunksStored;
    private Integer totalChunks;
    private String status;
    private Integer totalCharacters;
}
