package com.askoxy.emailautomation.entity;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApprovalContext {
    private String clientEmail;
    private String clientName;
    private String emailSubject;
    private String emailBody;       // final generated HTML/text
    private String campaignId;      // for logging/tracing
    private String feedbackHistory; // for regeneration if rejected
}