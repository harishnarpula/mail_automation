package com.askoxy.emailautomation.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GeneratedEmailDto {
    private String subject;
    private String body;
}