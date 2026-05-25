package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentFormatterService {

    private final AIService aiService;

    public FormatDto format(String rawContent,
                            String imageUrl,
                            String videoUrl,
                            List<String> platforms) {

        Map<String, PlatformContent> formatted = new LinkedHashMap<>();

        for (String platform : platforms) {
            try {
                PlatformContent pc = formatForPlatform(
                        platform.toUpperCase(), rawContent, imageUrl);
                formatted.put(platform.toUpperCase(), pc);
                log.info("Formatted for {}", platform);

            } catch (Exception ex) {
                log.error("Format failed for {}: {}", platform, ex.getMessage());
            }
        }

        return FormatDto.builder()
                .formattedContent(formatted)
                .build();
    }

    private PlatformContent formatForPlatform(String platform, String rawContent, String imageUrl) {

        String systemPrompt = getSystemPrompt(platform);
        String userPrompt = """
                Original Content:
                %s

                Format this for %s. Follow all rules strictly.
                Fix any spelling or grammar mistakes in the output.
                Return ONLY the formatted text. No explanations.
                """.formatted(rawContent, platform);

        String formatted = aiService.chat(systemPrompt, userPrompt);
        return buildPlatformContent(platform, formatted, imageUrl);
    }

    private String getSystemPrompt(String platform) {
        return switch (platform) {

            case "LINKEDIN" -> """
                    You are a LinkedIn content expert.
                    Format rules:
                    - Professional, authoritative tone
                    - Start with a strong hook (first line is most important)
                    - Max 1300 characters for best reach
                    - Add 3-5 relevant professional hashtags at end
                    - Use line breaks for readability
                    - No excessive emojis — max 2-3
                    - End with a clear call to action or question
                    - First person voice as CEO
                    - Zero spelling or grammar mistakes
                    """;

            case "FACEBOOK" -> """
                    You are a Facebook content expert.
                    Format rules:
                    - Warm, conversational, engaging tone
                    - 100-250 words ideal
                    - First 2 lines must hook the reader (shown before "See more")
                    - Use 2-5 emojis naturally in text
                    - Add 3-5 hashtags at end
                    - Ask a question to boost engagement
                    - Personal, story-driven tone
                    - Zero spelling or grammar mistakes
                    """;

            case "INSTAGRAM" -> """
                    You are an Instagram content expert.
                    Format rules:
                    - Attention-grabbing first line (shows in feed)
                    - Caption max 2200 characters
                    - Use emojis generously and naturally
                    - Add 20-30 hashtags at end separated by line break
                    - Mix popular and niche hashtags
                    - Include a call to action (link in bio, comment, etc.)
                    - Visual, lifestyle-oriented language
                    - Zero spelling or grammar mistakes
                    """;

            case "WHATSAPP" -> """
                    You are a WhatsApp broadcast content expert.
                    Format rules:
                    - Short, punchy, personal
                    - Max 200 words
                    - Use *bold* for emphasis (WhatsApp markdown)
                    - Use emojis naturally
                    - Conversational, direct tone
                    - One clear message or announcement
                    - End with a simple CTA (reply, visit link, call)
                    - No hashtags needed
                    - Zero spelling or grammar mistakes
                    """;

            case "BLOG" -> """
                    You are an expert blog writer for askoxy.ai and Radha Sir (CEO, AskOxy).

                    Format rules:
                    - Use the original content as SOURCE MATERIAL — extract the key ideas and facts
                    - Write FRESH, ORIGINAL blog content — do NOT copy or repeat the source text word-for-word
                    - Rewrite and expand into a complete, polished blog post in your own words
                    - SEO-friendly title (include main keyword) — 6-12 words
                    - The H1 heading (# Heading) MUST exactly match the blog title
                    - Engaging introduction paragraph
                    - 2-3 main sections with clear headings (## heading)
                    - Bullet points where relevant
                    - Conclusion with call to action
                    - 600-1000 words ideal
                    - Write naturally in first person
                    - Only use phrases like:
                      "As Radha Sir"
                      "As CEO"
                      "As Founder"
                      if explicitly relevant to the content
                    - Otherwise write naturally and directly
                    - Do NOT overuse identity references
                    - Professional but warm, accessible tone
                    - No hashtags in the blog body
                    - Add meta description at the very end:
                    META: [one line SEO description under 160 chars]
                    - Zero spelling or grammar mistakes
                    """;

            case "EMAIL" -> """
                    You are an email marketing expert.
                    Format rules:
                    - Write: SUBJECT: [compelling subject line]
                    - Then blank line
                    - Greeting: Hi [First Name],
                    - Short intro paragraph (2-3 sentences)
                    - Main content in 2-3 short paragraphs
                    - One clear CTA button text: CTA: [button text]
                    - Professional signature:
                      Warm regards,
                      Radha Sir
                      CEO, AskOxy Group
                    - Max 300 words total
                    - Zero spelling or grammar mistakes
                    """;

            default -> """
                    Format this content appropriately for social media.
                    Keep it engaging and professional.
                    Fix any spelling or grammar mistakes.
                    """;
        };
    }

    private PlatformContent buildPlatformContent(String platform,
                                                 String formattedText,
                                                 String imageUrl) {
        String subject = null;
        String title = null;
        String hashtags = null;

        if (platform.equals("EMAIL") && formattedText.contains("SUBJECT:")) {
            for (String line : formattedText.split("\n")) {
                if (line.startsWith("SUBJECT:")) {
                    subject = line.replace("SUBJECT:", "").trim();
                    break;
                }
            }
        }

        if (platform.equals("BLOG") && formattedText.contains("#")) {
            for (String line : formattedText.split("\n")) {
                if (line.startsWith("# ")) {
                    title = line.replace("# ", "").trim();
                    break;
                }
            }
        }

        if (formattedText.contains("#")) {
            StringBuilder tags = new StringBuilder();
            for (String word : formattedText.split("\\s+")) {
                if (word.startsWith("#")) tags.append(word).append(" ");
            }
            hashtags = tags.toString().trim();
        }

        int limit = switch (platform) {
            case "LINKEDIN"  -> 3000;
            case "FACEBOOK"  -> 63206;
            case "INSTAGRAM" -> 2200;
            case "WHATSAPP"  -> 4096;
            case "BLOG"      -> 99999;
            case "EMAIL"     -> 5000;
            default          -> 5000;
        };

        String limitLabel = switch (platform) {
            case "LINKEDIN"  -> "3000 chars max";
            case "INSTAGRAM" -> "2200 chars max";
            case "WHATSAPP"  -> "Short message";
            case "EMAIL"     -> "Keep it brief";
            case "BLOG"      -> "600-1200 words";
            default          -> "";
        };

        return PlatformContent.builder()
                .platform(platform)
                .text(formattedText)
                .hashtags(hashtags)
                .subject(subject)
                .title(title)
                .imageUrl(imageUrl)
                .charCount(formattedText.length())
                .limit(limitLabel)
                .build();
    }
}
