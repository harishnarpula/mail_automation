package com.askoxy.emailautomation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QdrantStorageService {

    private final VectorStore vectorStore;

    public String store(String fileId, List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("No chunks available to store in Qdrant.");
        }

        String vectorStoreId = UUID.randomUUID().toString();

        List<Document> documents = chunks.stream()
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .map(chunk -> new Document(chunk, Map.of(
                        "fileId", fileId,
                        "vectorStoreId", vectorStoreId
                )))
                .toList();

        if (documents.isEmpty()) {
            throw new IllegalArgumentException("All chunks were empty after filtering; nothing to index.");
        }

        vectorStore.add(documents);
        log.info("Stored {} chunks in Qdrant, vectorStoreId={}", documents.size(), vectorStoreId);
        return vectorStoreId;
    }
}
