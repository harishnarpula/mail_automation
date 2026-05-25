package com.askoxy.emailautomation.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloneApprovalRequest {

    private String entityId;       // videoId OR contentId
    private String entityType;     // "VIDEO" or "CONTENT"  ← ADD THIS
    private String editedContent;
    private Boolean confirmed;
}