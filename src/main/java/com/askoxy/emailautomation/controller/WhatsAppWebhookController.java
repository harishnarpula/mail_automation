package com.askoxy.emailautomation.controller;

import com.askoxy.emailautomation.service.ApprovalOrchestrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final ApprovalOrchestrationService approvalOrchestrationService;
    private final ObjectMapper objectMapper;

    @Value("${ultramsg.admin-number}")
    private String approvedAdminNumber;

    @PostMapping("/webhook/whatsapp")
    public ResponseEntity<String> webhook(
            @RequestBody(required = false) String body
    ) {

        log.info("=== WEBHOOK HIT ===");
        log.info("RAW BODY: {}", body);

        try {

            if (body == null || body.isBlank()) {
                return ResponseEntity.ok("empty_body");
            }

            JsonNode root = objectMapper.readTree(body);

            String eventType =
                    root.path("event_type").asText();

            log.info("Event Type: {}", eventType);

            // Process only received messages
            if (!"message_received".equals(eventType)) {
                log.info("Ignored event: {}", eventType);
                return ResponseEntity.ok("ignored_event");
            }

            JsonNode data = root.path("data");

            boolean fromMe =
                    data.path("fromMe").asBoolean();

            // Ignore messages sent by your own WhatsApp
            if (fromMe) {
                log.info("Ignoring own message");
                return ResponseEntity.ok("own_message");
            }

            String senderNumber =
                    data.path("from").asText();

            String normalizedSender =
                    senderNumber
                            .replace("@c.us", "")
                            .trim();

            String message =
                    data.path("body")
                            .asText("")
                            .trim();

// Allow only admin number
            String adminNormalized = approvedAdminNumber == null
                    ? ""
                    : approvedAdminNumber.replace("+", "").trim();

            if (!adminNormalized.equals(normalizedSender)) {

                log.warn(
                        "Unauthorized WhatsApp approval attempt from: {} (expected admin={})",
                        normalizedSender, adminNormalized
                );

                return ResponseEntity.ok("unauthorized");
            }

            String pushName =
                    data.path("pushname").asText();

            log.info("Incoming message");
            log.info("From: {}", senderNumber);
            log.info("Name: {}", pushName);
            log.info("Message: {}", message);

            // Process admin reply
            if (!message.isBlank()) {

                log.info("Calling processAdminReply()");

                approvalOrchestrationService
                        .processAdminReply(message);
            }

            return ResponseEntity.ok("success");

        } catch (Exception e) {

            log.error("Webhook processing failed", e);

            return ResponseEntity.ok("error");
        }
    }
}
