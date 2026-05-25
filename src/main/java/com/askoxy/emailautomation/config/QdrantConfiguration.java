package com.askoxy.emailautomation.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class QdrantConfiguration {

    private final QdrantClient qdrantClient;

    public QdrantConfiguration(@org.springframework.beans.factory.annotation.Qualifier("radhaAiQdrantClient") QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    @Value("${radhaclone.qdrant.collection-name}")
    private String collectionName;

    @PostConstruct
    public void initCollection() {

        try {

            boolean exists = qdrantClient.listCollectionsAsync().get()
                    .stream()
                    .anyMatch(c -> c.equals(collectionName));

            if (exists) {
                log.info("Qdrant collection already exists: {}", collectionName);
            } else {
                qdrantClient.createCollectionAsync(
                        collectionName,
                        Collections.VectorParams.newBuilder()
                                .setSize(1536)
                                .setDistance(Collections.Distance.Cosine)
                                .build()
                ).get();
                log.info("Qdrant collection created successfully: {}", collectionName);
            }

        } catch (Exception e) {
            log.warn("Qdrant collection init skipped: {}", e.getMessage());
        }
    }
}
