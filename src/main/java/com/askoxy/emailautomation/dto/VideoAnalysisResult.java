package com.askoxy.emailautomation.dto;

import lombok.*;
import java.util.List;

/**
 * VideoAnalysisResult — full output of the 3-step analysis pipeline.
 *
 * Step 1 → audioTranscript   : verbatim speech from video
 * Step 2 → visualContent     : text/graphics shown on screen
 * Step 3 → reasoningNotes    : how both sources were combined
 *           reasonedContent  : the final best synthesised content
 *           summary / keyPoints / tone / mainTopic / draftPost (unchanged for compatibility)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoAnalysisResult {

    // ── Step 1 output ──────────────────────────────────────────────────────

    /** Verbatim transcript of everything spoken in the video. */
    private String audioTranscript;

    // ── Step 2 output ──────────────────────────────────────────────────────

    /** All text, slide content, graphics, captions found on screen. */
    private String visualContent;

    // ── Step 3 outputs ─────────────────────────────────────────────────────

    /** 1-2 sentence explanation of how audio + visual were combined. */
    private String reasoningNotes;

    /**
     * The final, best, most complete synthesised content — 200-300 words.
     * Derived from BOTH audio transcript AND visual content via AI reasoning.
     * This is the primary field to use as approvedContent.
     */
    private String reasonedContent;

    // ── Existing fields (kept for backward compatibility) ──────────────────

    /** 2-3 sentence summary derived from both sources. */
    private String summary;

    /** Top 5 key points extracted from both audio and visual. */
    private List<String> keyPoints;

    /** Tone: professional / casual / motivational / educational */
    private String tone;

    /** Main topic identified from combined analysis. */
    private String mainTopic;

    /** 150-220 word LinkedIn-style post in first person as Radha. */
    private String draftPost;

    /** Compelling headline 6-12 words from the video. */
    private String title;

    /** Hook/opening sentence for the content. */
    private String intro;

    /** 200-300 word main body in first person as Radha. */
    private String body;

    /** 1-2 sentence strong closing takeaway. */
    private String closing;
}