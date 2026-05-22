package com.askoxy.emailautomation.controller;

import com.askoxy.emailautomation.dto.CampaignRequest;
import com.askoxy.emailautomation.dto.EmailAutomationResponse;
import com.askoxy.emailautomation.dto.PdfUploadResponse;
import com.askoxy.emailautomation.service.EmailAutomationService;
import com.askoxy.emailautomation.service.PdfIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EmailAutomationController {

    private final PdfIngestionService pdfIngestionService;
    private final EmailAutomationService emailAutomationService;

    @PostMapping("/pdf/upload")
    public ResponseEntity<PdfUploadResponse> uploadPdf(
            @RequestParam("file") MultipartFile file) {

        log.info("[Controller] PDF upload request - fileName={}", file.getOriginalFilename());
        PdfUploadResponse response = pdfIngestionService.ingest(file);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/email/send-campaign")
    public ResponseEntity<EmailAutomationResponse> sendCampaign(
            @RequestBody CampaignRequest request) {

        log.info("[Controller] Campaign request — client={}", request.getClientEmail());

        EmailAutomationResponse response = emailAutomationService.startCampaign(
                request.getClientName(),
                request.getClientEmail()
        );
        return ResponseEntity.ok(response);
    }

    private List<String> extractFileIds(Map<String, Object> request) {
        List<String> fileIds = new ArrayList<>();
        Object fileIdsRaw = request.get("fileIds");
        if (fileIdsRaw instanceof List<?> list) {
            for (Object value : list) {
                String id = toStringOrNull(value);
                if (id != null && !id.isBlank()) {
                    fileIds.add(id);
                }
            }
        }

        if (fileIds.isEmpty()) {
            String singleFileId = toStringOrNull(request.get("fileId"));
            if (singleFileId != null && !singleFileId.isBlank()) {
                fileIds.add(singleFileId);
            }
        }

        return fileIds;
    }

    private String toStringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
