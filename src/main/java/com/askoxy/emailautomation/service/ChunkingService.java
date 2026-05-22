package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.dto.DocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChunkingService {

    private static final int MAX_CHUNK_SIZE = 1200;
    private static final int OVERLAP_SIZE = 200;
    private static final int MIN_CHUNK_SIZE = 150;

    public List<DocumentChunk> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();

        String normalized = text
                .replace("\r\n", "\n").replace("\r", "\n")
                .replaceAll("[ \t]+", " ")
                .replaceAll("\n{3,}", "\n\n").trim();

        String[] paragraphs = normalized.split("\\n\\s*\\n");
        List<DocumentChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isBlank()) continue;

            if (current.length() + para.length() > MAX_CHUNK_SIZE) {
                saveChunk(chunks, current.toString(), index++);
                String overlap = current.length() > OVERLAP_SIZE
                        ? current.substring(current.length() - OVERLAP_SIZE)
                        : current.toString();
                current = new StringBuilder(overlap);
            }
            current.append(para).append("\n\n");
        }

        if (!current.isEmpty()) saveChunk(chunks, current.toString(), index);

        // Fallback: if normalized text exists but all chunks were filtered out by min size,
        // keep one chunk so downstream vector storage never gets an empty request.
        if (chunks.isEmpty() && !normalized.isBlank()) {
            chunks.add(DocumentChunk.builder()
                    .chunkIndex(0)
                    .chunkText(normalized)
                    .characterCount(normalized.length())
                    .build());
            log.warn("Chunk fallback applied: created single chunk below MIN_CHUNK_SIZE threshold");
        }

        log.info("Created {} chunks", chunks.size());
        return chunks;
    }

    private void saveChunk(List<DocumentChunk> chunks, String text, int idx) {
        String clean = text.trim();
        if (clean.length() < MIN_CHUNK_SIZE) return;
        chunks.add(DocumentChunk.builder()
                .chunkIndex(idx)
                .chunkText(clean)
                .characterCount(clean.length())
                .build());
    }
}
