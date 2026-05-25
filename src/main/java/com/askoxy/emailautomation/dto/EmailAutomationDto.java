package com.askoxy.emailautomation.dto;

import lombok.*;
import org.springframework.web.multipart.MultipartFile;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailAutomationDto {
    private MultipartFile file;
    private String clientName;
    private String clientEmail;
    private boolean success;
    private String message;
    private GeneratedEmailDto generatedEmail;
}
