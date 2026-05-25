package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.dto.VideoAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * GeminiVideoService — 3-step video analysis pipeline (OpenAI stack).
 *
 * Step 1 → Whisper API           : verbatim audio transcript
 * Step 2 → FFmpeg + GPT-4o Vision: extract video frames → read on-screen content
 * Step 3 → GPT-4o synthesis      : reason over BOTH → best final content
 *
 * Falls back gracefully if FFmpeg is not installed (Step 2 skipped with notice).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiVideoService {

    private final ObjectMapper objectMapper;
    private final AIService    aiService;

    @Value("${spring.ai.openai.api-key}")
    private String openAiKey;

    private static final String WHISPER_URL     = "https://api.openai.com/v1/audio/transcriptions";
    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String GPT_MODEL       = "gpt-4o";
    private static final int    FRAME_COUNT     = 6;   // frames extracted per video

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    public VideoAnalysisResult analyzeVideo(MultipartFile videoFile) throws Exception {

        log.info("=== VIDEO ANALYSIS START | file={} size={}MB ===",
                videoFile.getOriginalFilename(),
                videoFile.getSize() / (1024 * 1024));

        Path tempVideo = saveTempVideo(videoFile);

        try {
            // ── Step 1: Whisper — audio transcript ────────────────────────
            log.info("Step 1 → Whisper transcription...");
            String audioTranscript = transcribeWithWhisper(videoFile);
            log.info("Step 1 DONE | chars={}", audioTranscript.length());

            // ── Step 2: FFmpeg + GPT-4o Vision — visual content ───────────
            log.info("Step 2 → Frame extraction + GPT-4o Vision...");
            String visualContent = extractVisualContent(tempVideo);
            log.info("Step 2 DONE | chars={}", visualContent.length());

            // ── Step 3: GPT-4o — reason + synthesise final content ────────
            log.info("Step 3 → GPT-4o synthesis...");
            VideoAnalysisResult result = synthesizeFinalContent(
                    audioTranscript, visualContent, videoFile.getOriginalFilename());
            log.info("Step 3 DONE | topic={}", result.getMainTopic());

            return result;

        } finally {
            try { Files.deleteIfExists(tempVideo); } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — WHISPER AUDIO TRANSCRIPTION
    // ─────────────────────────────────────────────────────────────────────────

    private String transcribeWithWhisper(MultipartFile videoFile) throws Exception {

        RestTemplate rt = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(openAiKey);

        ByteArrayResource fileResource = new ByteArrayResource(videoFile.getBytes()) {
            @Override public String getFilename() {
                return videoFile.getOriginalFilename() != null
                        ? videoFile.getOriginalFilename() : "video.mp4";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file",            fileResource);
        body.add("model",           "whisper-1");
        body.add("response_format", "text");
        body.add("language",        "en");   // force English — prevents wrong language detection
        body.add("prompt",
                "This is a professional video by Radha, CEO of AskOxy. " +
                        "Transcribe every word accurately including technical and business terms.");

        ResponseEntity<String> response = rt.exchange(
                WHISPER_URL, HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        String t = response.getBody();
        return (t == null || t.isBlank()) ? "[No speech detected in video]" : t.trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — FFMPEG FRAME EXTRACTION + GPT-4o VISION
    // ─────────────────────────────────────────────────────────────────────────

    private String extractVisualContent(Path videoPath) {
        List<String> base64Frames = extractFrames(videoPath);
        if (base64Frames.isEmpty()) {
            log.warn("Step 2 SKIPPED — FFmpeg unavailable or no frames extracted");
            return "[Visual extraction skipped — FFmpeg not installed on server. " +
                    "To enable: install FFmpeg and restart the application.]";
        }
        log.info("Step 2 → Sending {} frames to GPT-4o Vision", base64Frames.size());
        return callGptVision(base64Frames);
    }

    /**
     * Runs FFmpeg to extract FRAME_COUNT evenly-spaced JPEG frames.
     * Returns base64-encoded frames, or empty list if FFmpeg is absent.
     */
    private List<String> extractFrames(Path videoPath) {

        List<String> frames = new ArrayList<>();
        Path tempDir = null;

        try {
            // Verify FFmpeg is installed
            new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start().waitFor();

            tempDir = Files.createTempDirectory("radha_frames_");

            // Get video duration
            double durationSecs = getVideoDuration(videoPath);
            if (durationSecs <= 0) durationSecs = 60;

            // fps filter: 1 frame every N seconds evenly across video
            double interval = durationSecs / (FRAME_COUNT + 1);
            String fpsFilter    = String.format("fps=1/%.2f", interval);
            String framePattern = tempDir.resolve("frame_%02d.jpg").toString();

            List<String> cmd = Arrays.asList(
                    "ffmpeg", "-i", videoPath.toString(),
                    "-vf",    fpsFilter,
                    "-vframes", String.valueOf(FRAME_COUNT),
                    "-q:v",   "2",          // high-quality JPEG
                    "-s",     "1280x720",   // cap size for API payload
                    framePattern, "-y"
            );

            log.info("FFmpeg: {}", String.join(" ", cmd));
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            proc.waitFor();

            File[] frameFiles = tempDir.toFile().listFiles(
                    f -> f.getName().startsWith("frame_") && f.getName().endsWith(".jpg")
            );

            if (frameFiles != null) {
                Arrays.sort(frameFiles, Comparator.comparing(File::getName));
                for (File f : frameFiles) {
                    byte[] bytes = Files.readAllBytes(f.toPath());
                    frames.add(Base64.getEncoder().encodeToString(bytes));
                    log.info("Frame: {} ({}KB)", f.getName(), bytes.length / 1024);
                }
            }

        } catch (Exception e) {
            log.warn("Frame extraction failed: {}", e.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    File[] files = tempDir.toFile().listFiles();
                    if (files != null) for (File f : files) f.delete();
                    tempDir.toFile().delete();
                } catch (Exception ignored) {}
            }
        }

        return frames;
    }

    private double getVideoDuration(Path videoPath) {
        try {
            Process proc = new ProcessBuilder(
                    "ffprobe", "-v", "quiet",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoPath.toString()
            ).redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            return Double.parseDouble(out);
        } catch (Exception e) {
            log.warn("Could not read video duration: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Sends extracted frames to GPT-4o Vision.
     * Focused prompt: read and extract ONLY on-screen text/graphics.
     */
    @SuppressWarnings("unchecked")
    private String callGptVision(List<String> base64Frames) {

        RestTemplate rt = new RestTemplate();

        List<Map<String, Object>> contentParts = new ArrayList<>();

        // Visual-only focused prompt
        contentParts.add(Map.of(
                "type", "text",
                "text", """
                        You are a visual content analyst reviewing frames from a video.
                        
                        Your ONLY job: extract ALL text and graphical content visible on screen.
                        
                        Extract:
                        - Slide titles, bullet points, body text
                        - Headlines, captions, overlays, subtitles shown on video
                        - Product names, brand names, logos (as text)
                        - Charts or graphs (describe what data they show)
                        - URLs, phone numbers, email addresses visible
                        - Any text appearing anywhere in the frame
                        
                        Rules:
                        - Label each frame: [Frame 1], [Frame 2], etc.
                        - Do NOT describe the speaker's appearance or background
                        - Do NOT transcribe speech/audio
                        - If a frame has no meaningful on-screen text: write [Frame N: no on-screen text]
                        - Return ONLY the extracted visual content as plain text
                        """
        ));

        for (int i = 0; i < base64Frames.size(); i++) {
            contentParts.add(Map.of("type", "text", "text", "[Frame " + (i + 1) + "]"));
            contentParts.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of(
                            "url",    "data:image/jpeg;base64," + base64Frames.get(i),
                            "detail", "high"
                    )
            ));
        }

        Map<String, Object> payload = Map.of(
                "model",      GPT_MODEL,
                "messages",   List.of(Map.of("role", "user", "content", contentParts)),
                "max_tokens", 2000
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiKey);

        ResponseEntity<Map> response = rt.exchange(
                OPENAI_CHAT_URL, HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        return extractChatText(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 3 — GPT-4o REASONING + SYNTHESIS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Receives the two plain-text extractions.
     * Reasons over them to produce the final best JSON content package.
     * No video re-sent — pure text-to-text reasoning.
     */
    private VideoAnalysisResult synthesizeFinalContent(
            String audioTranscript,
            String visualContent,
            String fileName) {

        String systemPrompt = """
                You are a content editor for Radha, CEO of AskOxy.
                You receive audio transcript and visual content extracted from her video.

                STRICT RULES — follow exactly:
                - Write ONLY what Radha actually said or what was shown in the video
                - Do NOT add any ideas, opinions, or content not present in the video
                - Do NOT fill gaps with imagination or general knowledge
                - Do NOT write philosophical or unrelated content
                - If transcript is unclear, use only visual content to write
                - If both are unclear, write only what little you understood — do not invent
                - Always write output in clear English only
                - Always write in first person as Radha Sir
                Return ONLY valid JSON — no markdown, no code blocks.
                """;

        String userPrompt = """
                Video: %s
                
                ═══════════════════════════════════════
                AUDIO TRANSCRIPT (what Radha SAID):
                ═══════════════════════════════════════
                %s
                
                ═══════════════════════════════════════
                VISUAL CONTENT (what was SHOWN on screen):
                ═══════════════════════════════════════
                %s
                
                ═══════════════════════════════════════
                INSTRUCTIONS:
                ═══════════════════════════════════════
                - Cross-reference both sources for the COMPLETE picture
                - If visuals contain data/facts not in speech → include them
                - If speech gives context visuals lack → use that context
                - Resolve gaps between the two sources intelligently
                
                Return ONLY this JSON:
                {
                  "audioTranscript": "<cleaned English transcript of exactly what was said>",
                  "visualContent": "<exact on-screen text and graphics seen in video>",
                  "reasoningNotes": "<1-2 sentences on what the video is actually about>",
                  "mainTopic": "<precise topic in 4-8 words — only from video content>",
                  "tone": "<professional/casual/motivational/educational>",
                  "title": "<compelling headline 6-12 words — only from what video actually covers>",
                  "summary": "<2-3 sentence overview of what Radha actually said in the video>",
                  "intro": "<1-2 sentence hook based strictly on video opening — no invention>",
                  "body": "<200-300 words main message — only what Radha said, first person, clear English>",
                  "closing": "<1-2 sentence closing — only what Radha concluded in the video>",
                  "keyPoints": [
                    "<key point 1 — only from video>",
                    "<key point 2>",
                    "<key point 3>",
                    "<key point 4>",
                    "<key point 5>"
                  ],
                  "draftPost": "<150-220 word post strictly based on video content, first person as Radha>",
                  "reasonedContent": "<300-400 words — intro+body+closing combined, strictly from video content, first person as Radha>"
                }
                """.formatted(fileName, audioTranscript, visualContent);

        String rawJson = aiService.chat(systemPrompt, userPrompt);
        return parseAnalysis(rawJson, audioTranscript, visualContent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private Path saveTempVideo(MultipartFile videoFile) throws Exception {
        String ext  = ".mp4";
        String name = videoFile.getOriginalFilename();
        if (name != null && name.contains(".")) ext = name.substring(name.lastIndexOf('.'));
        Path temp = Files.createTempFile("radha_video_", ext);
        Files.write(temp, videoFile.getBytes());
        log.info("Temp video: {}", temp);
        return temp;
    }

    @SuppressWarnings("unchecked")
    private String extractChatText(ResponseEntity<Map> response) {
        if (response.getBody() == null)
            throw new RuntimeException("OpenAI returned empty body");

        List<Map<String, Object>> choices =
                (List<Map<String, Object>>) response.getBody().get("choices");

        if (choices == null || choices.isEmpty())
            throw new RuntimeException("OpenAI returned no choices");

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = (String) message.get("content");

        if (content == null || content.isBlank())
            throw new RuntimeException("OpenAI returned blank content");

        return content.trim();
    }

    private VideoAnalysisResult parseAnalysis(
            String rawJson, String audioTranscript, String visualContent) {
        try {
            String clean = rawJson
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();
            return objectMapper.readValue(clean, VideoAnalysisResult.class);
        } catch (Exception ex) {
            log.error("JSON parse failed: {}", rawJson, ex);
            return VideoAnalysisResult.builder()
                    .audioTranscript(audioTranscript)
                    .visualContent(visualContent)
                    .reasoningNotes("JSON parse failed")
                    .summary("Parse failed — see reasonedContent")
                    .keyPoints(List.of("See reasonedContent"))
                    .tone("unknown")
                    .mainTopic("unknown")
                    .draftPost(rawJson)
                    .reasonedContent(rawJson)
                    .build();
        }
    }
}