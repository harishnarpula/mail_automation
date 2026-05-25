package com.askoxy.emailautomation.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class QdrantConfig {

    // --- Useronboard Qdrant Properties ---
    @Value("${useronboard.qdrant.host}")
    private String useronboardHost;

    @Value("${useronboard.qdrant.port:6334}")
    private int useronboardPort;

    @Value("${useronboard.qdrant.api-key:}")
    private String useronboardApiKey;

    @Value("${useronboard.qdrant.collection-name:askoxycollection}")
    private String useronboardCollectionName;

    @Value("${useronboard.qdrant.use-tls:true}")
    private boolean useronboardUseTls;

    // --- Radhaclone Qdrant Properties ---
    @Value("${radhaclone.qdrant.host}")
    private String radhacloneHost;

    @Value("${radhaclone.qdrant.port:6334}")
    private int radhaclonePort;

    @Value("${radhaclone.qdrant.api-key:}")
    private String radhacloneApiKey;

    @Value("${radhaclone.qdrant.collection-name:askoxy_live}")
    private String radhacloneCollectionName;

    @Value("${radhaclone.qdrant.use-tls:true}")
    private boolean radhacloneUseTls;

    @Primary
    @Bean(name = "emailAutomationQdrantClient")
    public QdrantClient emailAutomationQdrantClient() {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(useronboardHost, useronboardPort, useronboardUseTls);
        if (useronboardApiKey != null && !useronboardApiKey.isBlank()) {
            builder.withApiKey(useronboardApiKey);
        }
        return new QdrantClient(builder.build());
    }

    @Primary
    @Bean(name = "emailAutomationVectorStore")
    public QdrantVectorStore emailAutomationVectorStore(QdrantClient emailAutomationQdrantClient, EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(emailAutomationQdrantClient, embeddingModel)
                .collectionName(useronboardCollectionName)
                .build();
    }

    @Bean(name = "radhaAiQdrantClient")
    public QdrantClient radhaAiQdrantClient() {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(radhacloneHost, radhaclonePort, radhacloneUseTls);
        if (radhacloneApiKey != null && !radhacloneApiKey.isBlank()) {
            builder.withApiKey(radhacloneApiKey);
        }
        return new QdrantClient(builder.build());
    }

    @Bean(name = "radhaAiVectorStore")
    public QdrantVectorStore radhaAiVectorStore(QdrantClient radhaAiQdrantClient, EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(radhaAiQdrantClient, embeddingModel)
                .collectionName(radhacloneCollectionName)
                .build();
    }
}
