package com.askoxy.emailautomation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ultramsg")
public class UltraMsgConfig {

    // Bound from application.properties — ultramsg.instance-id
    private String instanceId;

    // Full API URL hardwired: https://api.ultramsg.com/instance16343/messages/chat
    private String apiUrl;

    // UltraMsg token — cp9d28fm19vv9nl4
    private String token;

    // Admin WhatsApp in international format without + e.g. 919876543210
    private String adminNumber;
}
