package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.dto.PdfUploadResponse;
import com.askoxy.emailautomation.dto.DocumentChunk;
import com.askoxy.emailautomation.entity.UploadedFile;
import com.askoxy.emailautomation.repository.UploadedFileRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfIngestionService {

    private final PdfExtractionService pdfExtractionService;
    private final ChunkingService chunkingService;
    private final QdrantStorageService qdrantStorageService;
    private final UploadedFileRepository uploadedFileRepository;

    public PdfUploadResponse ingest(MultipartFile file) {

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
            String extractedText = pdfExtractionService.extract(file);
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
            List<String> chunkTexts = chunks.stream()
                    .map(DocumentChunk::getChunkText).toList();
            String vectorStoreId = qdrantStorageService.store(fileId, chunkTexts);

            uploadedFile.setVectorStoreId(vectorStoreId);
            uploadedFile.setTotalChunks(chunks.size());
            uploadedFile.setUploadStatus("COMPLETED");
            uploadedFileRepository.save(uploadedFile);

            log.info("[PdfIngestion] Completed fileId={} chunks={}", fileId, chunks.size());

            return PdfUploadResponse.builder()
                    .success(true)
                    .message("PDF uploaded and indexed successfully")
                    .fileId(fileId)
                    .fileName(file.getOriginalFilename())
                    .totalChunks(chunks.size())
                    .build();

        } catch (Exception ex) {
            uploadedFile.setUploadStatus("FAILED");
            uploadedFileRepository.save(uploadedFile);
            log.error("[PdfIngestion] Failed for fileId={}", fileId, ex);
            throw new RuntimeException("PDF ingestion failed: " + ex.getMessage(), ex);
        }
    }
}
