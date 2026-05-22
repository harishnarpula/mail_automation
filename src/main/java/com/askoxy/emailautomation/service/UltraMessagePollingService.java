package com.askoxy.emailautomation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class UltraMessagePollingService {

    private final ApprovalOrchestrationService approvalOrchestrationService;
    private final RestTemplate restTemplate;

    @Value("${ultramsg.instance-id}")
    private String instanceId;

    @Value("${ultramsg.token}")
    private String token;

    @Value("${ultramsg.admin-number}")
    private String adminNumber;

    // Track already-processed message IDs to avoid duplicate processing.
    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();

    // Poll every 10 seconds.
    @Scheduled(fixedDelay = 10000)
    public void pollReceivedMessages() {
        try {
            String urlUnread = String.format(
                    "https://api.ultramsg.com/%s/messages/receive?token=%s&page=1&limit=50&status=1",
                    instanceId, token
            );
            String urlAll = String.format(
                    "https://api.ultramsg.com/%s/messages/receive?token=%s&page=1&limit=50",
                    instanceId, token
            );

            List<Map<String, Object>> messages = new ArrayList<>();
            collectMessages(urlUnread, messages);
            collectMessages(urlAll, messages);
            if (messages.isEmpty()) {
                return;
            }

            for (Map<String, Object> msg : messages) {
                String msgId = String.valueOf(msg.get("id"));
                String from = String.valueOf(msg.get("from"));
                String body = String.valueOf(msg.get("body"));
                String type = String.valueOf(msg.get("type"));

                if (msgId == null || msgId.isBlank() || processedIds.contains(msgId)) {
                    continue;
                }
                if (!"chat".equals(type)) {
                    continue;
                }
                if (body == null || body.isBlank() || "null".equalsIgnoreCase(body.trim())) {
                    continue;
                }

                String fromClean = normalizePhone(from);
                String adminClean = normalizePhone(adminNumber);
                if (!fromClean.equals(adminClean)) {
                    continue;
                }

                log.info("[Poll] Admin message found - id={} body={}", msgId, body);
                processedIds.add(msgId);
                approvalOrchestrationService.processAdminReply(body);
            }
        } catch (Exception e) {
            log.error("[Poll] Error polling UltraMsg", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void collectMessages(String url, List<Map<String, Object>> sink) {
        try {
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response == null) {
                return;
            }
            List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("messages");
            if (messages != null && !messages.isEmpty()) {
                sink.addAll(messages);
            }
        } catch (Exception e) {
            log.warn("[Poll] UltraMsg fetch failed for url={}", url);
        }
    }

    private String normalizePhone(String raw) {
        if (raw == null) return "";
        String beforeAt = raw.split("@")[0];
        return beforeAt.replaceAll("[^0-9]", "").trim();
    }
}
