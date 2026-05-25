package com.askoxy.emailautomation.service;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;

import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import com.askoxy.emailautomation.enums.FileType;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    // AIService injected for IMAGE (GPT-4 Vision) and AUDIO (Whisper) extraction
    private final AIService aiService;

    // ─────────────────────────────────────────────────────────────────────────
    // FILE TYPE DETECTION
    // ─────────────────────────────────────────────────────────────────────────

    public FileType detectFileType(MultipartFile file) {

        String fileName = file.getOriginalFilename();

        String ct = file.getContentType() != null
                ? file.getContentType().toLowerCase()
                : "";

        if (fileName != null && !fileName.isBlank()) {

            String lower = fileName.toLowerCase();

            if (lower.endsWith(".pdf"))   return FileType.PDF;

            if (lower.endsWith(".docx"))  return FileType.DOCX;

            if (lower.endsWith(".doc"))   return FileType.DOC;

            if (lower.endsWith(".txt"))   return FileType.TXT;

            if (lower.endsWith(".csv"))   return FileType.CSV;

            if (lower.endsWith(".pptx"))  return FileType.PPTX;

            if (lower.endsWith(".ppt"))   return FileType.PPTX_LEGACY;

            if (lower.endsWith(".xlsx"))  return FileType.XLSX;

            if (lower.endsWith(".xls"))   return FileType.XLS;

            if (lower.endsWith(".json"))  return FileType.JSON;

            if (lower.endsWith(".xml"))   return FileType.XML;

            if (lower.endsWith(".md")
                    || lower.endsWith(".markdown")) {

                return FileType.MD;
            }

            if (lower.endsWith(".html")
                    || lower.endsWith(".htm")) {

                return FileType.HTML;
            }

            if (lower.endsWith(".rtf")) {
                return FileType.RTF;
            }

            // IMAGE
            if (lower.endsWith(".png")
                    || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg")
                    || lower.endsWith(".webp")) {

                return FileType.IMAGE;
            }

            // AUDIO
            if (lower.endsWith(".mp3")
                    || lower.endsWith(".wav")
                    || lower.endsWith(".m4a")
                    || lower.endsWith(".aac")) {

                return FileType.AUDIO;
            }
        }

        // CONTENT TYPE DETECTION

        if (ct.contains("pdf")) {
            return FileType.PDF;
        }

        if (ct.contains("wordprocessingml")) {
            return FileType.DOCX;
        }

        if (ct.contains("msword")) {
            return FileType.DOC;
        }

        if (ct.contains("csv")) {
            return FileType.CSV;
        }

        if (ct.contains("presentationml")) {
            return FileType.PPTX;
        }

        if (ct.contains("spreadsheetml")) {
            return FileType.XLSX;
        }

        if (ct.contains("json")) {
            return FileType.JSON;
        }

        if (ct.contains("xml")) {
            return FileType.XML;
        }

        if (ct.contains("html")) {
            return FileType.HTML;
        }

        if (ct.contains("text/plain")) {
            return FileType.TXT;
        }

        if (ct.contains("rtf")) {
            return FileType.RTF;
        }

        if (ct.contains("image")) {
            return FileType.IMAGE;
        }

        if (ct.contains("audio")) {
            return FileType.AUDIO;
        }

        return FileType.UNKNOWN;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEXT EXTRACTION
    // ─────────────────────────────────────────────────────────────────────────

    public String extractText(MultipartFile file) {

        if (file.getSize() > 200 * 1024 * 1024) {

            throw new RuntimeException(
                    "File too large. Max allowed size is 200MB");
        }

        FileType fileType = detectFileType(file);

        log.info(
                "Extracting: file={} type={} size={}KB",
                file.getOriginalFilename(),
                fileType,
                file.getSize() / 1024);

        try {

            String extracted = switch (fileType) {

                case PDF -> extractPdf(file);

                case DOCX -> extractDocx(file);

                case DOC -> extractDoc(file);

                case TXT, MD -> extractPlainText(file);

                case CSV -> extractCsv(file);

                case PPTX -> extractPptx(file);

                case PPTX_LEGACY -> extractPpt(file);

                case XLSX -> extractXlsx(file);

                case XLS -> extractXls(file);

                case JSON -> extractPlainText(file);

                case XML -> extractXml(file);

                case HTML -> extractHtml(file);

                case RTF -> extractRtf(file);

                case IMAGE -> extractImageContent(file);

                case AUDIO -> extractAudioContent(file);

                default -> extractFallback(file);
            };

            return cleanExtractedText(extracted);

        } catch (Exception ex) {

            throw new RuntimeException(
                    "Extraction failed: "
                            + file.getOriginalFilename(),
                    ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PDF
    // ─────────────────────────────────────────────────────────────────────────

    private String extractPdf(MultipartFile file) {

        try (PDDocument document =
                     Loader.loadPDF(file.getBytes())) {

            PDFTextStripper stripper =
                    new PDFTextStripper();

            String extractedText =
                    stripper.getText(document);

            // CHECK TEXT QUALITY
            if (isValidPdfText(extractedText)) {

                log.info("Normal PDF text extracted");

                return extractedText;
            }

            log.info("Poor PDF text detected. Running OCR...");

            PDFRenderer renderer =
                    new PDFRenderer(document);

            ITesseract tesseract =
                    new Tesseract();

            // WINDOWS TESSERACT PATH
            tesseract.setDatapath(
                    "C:\\Program Files\\Tesseract-OCR\\tessdata");

            tesseract.setLanguage("eng");

            StringBuilder finalText =
                    new StringBuilder();

            for (int page = 0;
                 page < document.getNumberOfPages();
                 page++) {

                log.info("OCR Processing page {}",
                        page + 1);

                BufferedImage image =
                        renderer.renderImageWithDPI(
                                page,
                                300);

                String pageText =
                        tesseract.doOCR(image);

                finalText.append("\n\n")
                        .append("--- PAGE ")
                        .append(page + 1)
                        .append(" ---\n")
                        .append(pageText);
            }

            return finalText.toString();

        } catch (Exception e) {

            log.error("PDF extraction failed", e);

            return "[PDF extraction failed]";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOCX
    // ─────────────────────────────────────────────────────────────────────────
    private boolean isValidPdfText(String text) {

        if (text == null) {
            return false;
        }

        String cleaned = text.trim();

        // TOO SMALL
        if (cleaned.length() < 1000) {
            return false;
        }

        // COUNT REAL LETTERS
        long letters = cleaned.chars()
                .filter(Character::isLetter)
                .count();

        double ratio =
                (double) letters / cleaned.length();

        // LOW QUALITY / GARBAGE TEXT
        return ratio > 0.5;
    }
    private String extractDocx(MultipartFile file)
            throws IOException {

        try (XWPFDocument doc =
                     new XWPFDocument(file.getInputStream());

             XWPFWordExtractor e =
                     new XWPFWordExtractor(doc)) {

            return e.getText();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOC
    // ─────────────────────────────────────────────────────────────────────────

    private String extractDoc(MultipartFile file)
            throws IOException {

        try (HWPFDocument doc =
                     new HWPFDocument(file.getInputStream());

             WordExtractor e =
                     new WordExtractor(doc)) {

            return e.getText();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PLAIN TEXT
    // ─────────────────────────────────────────────────────────────────────────

    private String extractPlainText(MultipartFile file)
            throws IOException {

        StringBuilder sb = new StringBuilder();

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(
                        file.getInputStream(),
                        StandardCharsets.UTF_8))) {

            String line;

            while ((line = r.readLine()) != null) {

                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV
    // ─────────────────────────────────────────────────────────────────────────

    private String extractCsv(MultipartFile file)
            throws IOException {

        StringBuilder sb = new StringBuilder();

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(
                        file.getInputStream(),
                        StandardCharsets.UTF_8))) {

            String line;

            boolean first = true;

            while ((line = r.readLine()) != null) {

                sb.append(
                        first
                                ? "COLUMNS: " + line
                                : line.replace(",", " | ")
                ).append("\n");

                first = false;
            }
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PPTX
    // ─────────────────────────────────────────────────────────────────────────

    private String extractPptx(MultipartFile file)
            throws IOException {

        StringBuilder sb = new StringBuilder();

        try (XMLSlideShow ss =
                     new XMLSlideShow(file.getInputStream())) {

            int n = 1;

            for (XSLFSlide slide : ss.getSlides()) {

                sb.append("--- Slide ")
                        .append(n++)
                        .append(" ---\n");

                for (XSLFShape shape : slide.getShapes()) {

                    if (shape instanceof XSLFTextShape ts
                            && ts.getText() != null) {

                        sb.append(ts.getText().trim())
                                .append("\n");
                    }
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PPT LEGACY
    // ─────────────────────────────────────────────────────────────────────────

    private String extractPpt(MultipartFile file)
            throws IOException {

        StringBuilder sb = new StringBuilder();

        try (HSLFSlideShow ss =
                     new HSLFSlideShow(file.getInputStream())) {

            int n = 1;

            for (var slide : ss.getSlides()) {

                sb.append("--- Slide ")
                        .append(n++)
                        .append(" ---\n");

                for (var shape : slide.getShapes()) {

                    if (shape instanceof HSLFTextShape ts
                            && ts.getText() != null) {

                        sb.append(ts.getText().trim())
                                .append("\n");
                    }
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // XLSX
    // ─────────────────────────────────────────────────────────────────────────

    private String extractXlsx(MultipartFile file)
            throws IOException {

        try (XSSFWorkbook wb =
                     new XSSFWorkbook(file.getInputStream())) {

            return extractFromWorkbook(wb);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // XLS
    // ─────────────────────────────────────────────────────────────────────────

    private String extractXls(MultipartFile file)
            throws IOException {

        try (HSSFWorkbook wb =
                     new HSSFWorkbook(file.getInputStream())) {

            return extractFromWorkbook(wb);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WORKBOOK EXTRACTION
    // ─────────────────────────────────────────────────────────────────────────

    private String extractFromWorkbook(Workbook wb) {

        StringBuilder sb = new StringBuilder();

        DataFormatter fmt = new DataFormatter();

        for (int i = 0; i < wb.getNumberOfSheets(); i++) {

            Sheet sheet = wb.getSheetAt(i);

            sb.append("=== Sheet: ")
                    .append(sheet.getSheetName())
                    .append(" ===\n");

            for (Row row : sheet) {

                StringBuilder rowSb =
                        new StringBuilder();

                Iterator<Cell> it =
                        row.cellIterator();

                while (it.hasNext()) {

                    String val =
                            fmt.formatCellValue(it.next());

                    if (!val.isBlank()) {

                        rowSb.append(val)
                                .append(" | ");
                    }
                }

                String rowStr =
                        rowSb.toString().trim();

                if (!rowStr.isEmpty()) {

                    sb.append(rowStr).append("\n");
                }
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // XML
    // ─────────────────────────────────────────────────────────────────────────

    private String extractXml(MultipartFile file)
            throws IOException {

        return Jsoup.parse(
                new String(
                        file.getBytes(),
                        StandardCharsets.UTF_8)
        ).text();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML
    // ─────────────────────────────────────────────────────────────────────────

    private String extractHtml(MultipartFile file)
            throws IOException {

        return Jsoup.parse(
                new String(
                        file.getBytes(),
                        StandardCharsets.UTF_8)
        ).text();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RTF
    // ─────────────────────────────────────────────────────────────────────────

    private String extractRtf(MultipartFile file)
            throws IOException {

        return new String(
                file.getBytes(),
                StandardCharsets.UTF_8)

                .replaceAll(
                        "\\\\[a-z]+[-]?[0-9]*[ ]?",
                        " ")

                .replaceAll("[{}]", "")

                .replaceAll("\\s+", " ")

                .trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FALLBACK
    // ─────────────────────────────────────────────────────────────────────────

    private String extractFallback(MultipartFile file) {

        try {

            return new String(
                    file.getBytes(),
                    StandardCharsets.UTF_8);

        } catch (Exception e) {

            return "[Could not extract: "
                    + file.getOriginalFilename()
                    + "]";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLEAN TEXT
    // ─────────────────────────────────────────────────────────────────────────

    private String cleanExtractedText(String text) {

        if (text == null) {
            return "";
        }

        return text
                .replaceAll("\\p{C}", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("[ ]{2,}", " ")
                .trim();
    }



    // ─────────────────────────────────────────────────────────────────────────
    // IMAGE — GPT-4 Vision reads all visible text, banners, marketing content
    // Used ONLY by company upload API. No other flow affected.
    // ─────────────────────────────────────────────────────────────────────────

    private String extractImageContent(MultipartFile file) {
        try {
            log.info("Extracting image via GPT-4 Vision: {}", file.getOriginalFilename());
            String text = aiService.extractImageText(file);
            return (text != null && !text.isBlank())
                    ? text
                    : "[Image uploaded — no readable text detected]";
        } catch (Exception ex) {
            log.error("Image extraction failed: {}", ex.getMessage());
            return "[Image extraction failed: " + file.getOriginalFilename() + "]";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AUDIO — Whisper transcribes full speech content
    // Used ONLY by company upload API. No other flow affected.
    // ─────────────────────────────────────────────────────────────────────────

    private String extractAudioContent(MultipartFile file) {
        try {
            log.info("Transcribing audio via Whisper: {}", file.getOriginalFilename());
            String text = aiService.transcribe(file);
            return (text != null && !text.isBlank())
                    ? text
                    : "[Audio uploaded — no speech detected]";
        } catch (Exception ex) {
            log.error("Audio transcription failed: {}", ex.getMessage());
            return "[Audio transcription failed: " + file.getOriginalFilename() + "]";
        }
    }
}