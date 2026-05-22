package com.askoxy.emailautomation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final VectorStore vectorStore;

    public String retrieve(String query, String vectorStoreId, int topK) {

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression("vectorStoreId == '" + vectorStoreId + "'")
                .build();

        List<Document> docs = vectorStore.similaritySearch(request);

        String context = docs.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n\n" + b);

        log.info("Retrieved {} chunks for vectorStoreId={}", docs.size(), vectorStoreId);
        return context;
    }

    public List<String> retrieveByFileId(String fileId, String clientName) {
        SearchRequest request = SearchRequest.builder()
                .query(clientName)
                .topK(5)
                .filterExpression("fileId == '" + fileId + "'")
                .build();

        return vectorStore.similaritySearch(request)
                .stream()
                .map(Document::getText)
                .toList();
    }
}
