package com.askoxy.emailautomation.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class EmailAutomationRequest {
    private MultipartFile file;
    private String clientName;
    private String clientEmail;
}