package com.askoxy.emailautomation.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ContentApprovalRequest {
    private String contentId;
    private Boolean approved;
    private String feedback;
    private String editedContent;
}
