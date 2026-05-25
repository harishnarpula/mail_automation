package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.dto.UploadResponse;
import com.askoxy.emailautomation.dto.DocumentChunk;
import com.askoxy.emailautomation.entity.UploadedFile;
import com.askoxy.emailautomation.repository.UploadedFileRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfIngestionService {

    private final DocumentService documentService;
    private final ChunkingService chunkingService;
    private final VectorStore vectorStore;
    private final UploadedFileRepository uploadedFileRepository;

    public UploadResponse ingest(MultipartFile file) {

        String fileId = "FILE-" + UuidCreator.getTimeOrderedEpoch()
                .toString().substring(0, 12).toUpperCase();

        // --- Step 1: Save metadata ---
        UploadedFile uploadedFile = UploadedFile.builder()
                .fileId(fileId)
                .fileName(file.getOriginalFilename())
                .fileType("PDF")
                .uploadStatus("UPLOADING")
                .totalChunks(0)
                .build();
        uploadedFileRepository.save(uploadedFile);

        try {
            // --- Step 2: Extract ---
            String extractedText = documentService.extractText(file);
            if (extractedText == null || extractedText.isBlank()) {
                throw new IllegalArgumentException("No readable text found in PDF.");
            }
            uploadedFile.setUploadStatus("EXTRACTED");
            uploadedFileRepository.save(uploadedFile);

            // --- Step 3: Chunk ---
            List<DocumentChunk> chunks = chunkingService.chunk(extractedText);
            uploadedFile.setUploadStatus("CHUNKED");
            uploadedFileRepository.save(uploadedFile);

            // --- Step 4: Store in Qdrant ---
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("No chunks available to store in Qdrant.");
            }

            String vectorStoreId = UUID.randomUUID().toString();

            List<Document> documents = chunks.stream()
                    .map(DocumentChunk::getChunkText)
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

            uploadedFile.setVectorStoreId(vectorStoreId);
            uploadedFile.setTotalChunks(chunks.size());
            uploadedFile.setUploadStatus("COMPLETED");
            uploadedFileRepository.save(uploadedFile);

            log.info("[PdfIngestion] Completed fileId={} chunks={}", fileId, chunks.size());

            return UploadResponse.builder()
                    .success(true)
                    .message("PDF uploaded and indexed successfully")
                    .fileId(fileId)
                    .fileName(file.getOriginalFilename())
                    .totalChunks(chunks.size())
                    .chunksStored(chunks.size())
                    .status("COMPLETED")
                    .build();

        } catch (Exception ex) {
            uploadedFile.setUploadStatus("FAILED");
            uploadedFileRepository.save(uploadedFile);
            log.error("[PdfIngestion] Failed for fileId={}", fileId, ex);
            throw new RuntimeException("PDF ingestion failed: " + ex.getMessage(), ex);
        }
    }
}
