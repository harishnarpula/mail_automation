package com.askoxy.emailautomation.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class PdfExtractionService {

    public String extract(MultipartFile file) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.info("Extracted {} characters from PDF: {}", text.length(), file.getOriginalFilename());
            return text;
        } catch (Exception ex) {
            throw new RuntimeException("PDF extraction failed: " + file.getOriginalFilename(), ex);
        }
    }
}