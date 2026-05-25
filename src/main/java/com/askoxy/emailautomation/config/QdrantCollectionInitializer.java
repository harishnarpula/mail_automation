package com.askoxy.emailautomation.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class QdrantCollectionInitializer {

    @Value("${useronboard.qdrant.host}")
    private String host;

    @Value("${useronboard.qdrant.port}")
    private String port;

    @Value("${useronboard.qdrant.api-key}")
    private String apiKey;

    @Value("${useronboard.qdrant.collection-name}")
    private String collectionName;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void initializeCollection() {

        try {

            String baseUrl =
                    "https://" + host + ":6333";

            HttpHeaders headers = new HttpHeaders();
            headers.set("api-key", apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 1. Create collection
            String collectionUrl =
                    baseUrl + "/collections/" + collectionName;

            Map<String, Object> collectionBody = Map.of(
                    "vectors",
                    Map.of(
                            "size", 1536,
                            "distance", "Cosine"
                    )
            );

            HttpEntity<Map<String, Object>> collectionRequest =
                    new HttpEntity<>(collectionBody, headers);

            try {
                restTemplate.exchange(
                        collectionUrl,
                        HttpMethod.PUT,
                        collectionRequest,
                        String.class
                );

                System.out.println(
                        "Qdrant collection created"
                );

            } catch (Exception ignored) {
                System.out.println(
                        "Collection already exists"
                );
            }

            // 2. Create vectorStoreId payload index
            String indexUrl =
                    collectionUrl +
                            "/index";

            Map<String, Object> indexBody = Map.of(
                    "field_name",
                    "vectorStoreId",
                    "field_schema",
                    "keyword"
            );

            HttpEntity<Map<String, Object>> indexRequest =
                    new HttpEntity<>(indexBody, headers);

            try {
                restTemplate.exchange(
                        indexUrl,
                        HttpMethod.PUT,
                        indexRequest,
                        String.class
                );

                System.out.println(
                        "vectorStoreId index created"
                );

            } catch (Exception ignored) {
                System.out.println(
                        "Index already exists"
                );
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to initialize Qdrant",
                    e
            );
        }
    }
}