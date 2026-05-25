package com.askoxy.emailautomation.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class QdrantCollectionInitializer {

    @Value("${useronboard.qdrant.host}")
    private String host;

    @Value("${useronboard.qdrant.port:6333}")
    private String port;

    @Value("${useronboard.qdrant.api-key:}")
    private String apiKey;

    @Value("${useronboard.qdrant.collection-name:askoxycollection}")
    private String collectionName;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void initializeCollection() {

        try {
            if (!StringUtils.hasText(host)) {
                log.warn("Qdrant collection initializer skipped: host is empty");
                return;
            }

            String baseUrl =
                    "https://" + host + ":" + port;

            HttpHeaders headers = new HttpHeaders();
            if (StringUtils.hasText(apiKey)) {
                headers.set("api-key", apiKey);
            }
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

                log.info("Qdrant collection created: {}", collectionName);

            } catch (Exception ignored) {
                log.debug("Qdrant collection may already exist: {}", collectionName);
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

                log.info("Qdrant index created: vectorStoreId");

            } catch (Exception ignored) {
                log.debug("Qdrant index may already exist: vectorStoreId");
            }

        } catch (Exception e) {
            log.warn("Qdrant initializer skipped due to connectivity/config issue: {}", e.getMessage());
        }
    }
}
