package com.aiautomationservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RealtimeTokenController {

    @Value("${openai.api-key}")
    private String apiKey;

    private final RestClient restClient =
            RestClient.builder().build();

    @GetMapping("/api/realtime/token")
    public Map<String, Object> generateToken() {

        return restClient.post()
                .uri("https://api.openai.com/v1/realtime/client_secrets")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "session", Map.of(
                                "type", "realtime",
                                "model", "gpt-realtime"
                        )
                ))
                .retrieve()
                .body(Map.class);
    }
}