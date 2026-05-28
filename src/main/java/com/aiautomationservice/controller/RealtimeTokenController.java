package com.aiautomationservice.controller;

import com.aiautomationservice.dto.RealtimeTokenRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/realtime")
public class RealtimeTokenController {

    @Value("${openai.api-key}")
    private String apiKey;

    private final RestClient restClient =
            RestClient.builder().build();

    @PostMapping("/token")
    public Map<String, Object> generateToken(
            @RequestBody RealtimeTokenRequest request
    ) {

        Map<String, Object> session = new HashMap<>();
        session.put("type", "realtime");
        session.put("model", "gpt-realtime");

        // Optional voice
        if (request.getVoice() != null &&
                !request.getVoice().isBlank()) {
            session.put("voice", request.getVoice());
        }

        // Optional instructions
        if (request.getInstructions() != null &&
                !request.getInstructions().isBlank()) {
            session.put("instructions",
                    request.getInstructions());
        }

        Map<String, Object> requestBody =
                Map.of("session", session);

        return restClient.post()
                .uri("https://api.openai.com/v1/realtime/client_secrets")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(Map.class);
    }
}