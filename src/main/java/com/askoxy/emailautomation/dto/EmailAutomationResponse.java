package com.askoxy.emailautomation.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmailAutomationResponse {
    private boolean success;
    private String message;
    private String clientEmail;
    private GeneratedEmailDto generatedEmail;
}