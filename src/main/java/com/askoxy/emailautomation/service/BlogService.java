package com.askoxy.emailautomation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlogService {

    private final RestTemplate restTemplate;

    private static final String BLOG_API =
            "https://meta.oxyloans.com/api/marketing-service/campgin/addCampaignTypes";

    public String post(String title,
                       String content,
                       String imageUrl,
                       String videoUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("accept", "*/*");

            List<Map<String, Object>> imagesList = new ArrayList<>();
            imagesList.add(Map.of(
                    "imageUrl", imageUrl != null ? imageUrl : "",
                    "status", true
            ));

            Map<String, Object> campaignObject;
            if (videoUrl != null && !videoUrl.isBlank()) {
                campaignObject = Map.of(
                        "campaignDescription", content,
                        "campaignType",        title,
                        "socialMediaCaption",  title,
                        "campaignTypeAddBy",   "RADHA",
                        "images",              imagesList,
                        "videoUrl",            videoUrl,
                        "campainInputType",    "BLOG"
                );
            } else {
                campaignObject = Map.of(
                        "campaignDescription", content,
                        "campaignType",        title,
                        "socialMediaCaption",  title,
                        "campaignTypeAddBy",   "RADHA",
                        "images",              imagesList,
                        "campainInputType",    "BLOG"
                );
            }

            Map<String, Object> requestPayload = Map.of(
                    "askOxyCampaignDto", List.of(campaignObject)
            );

            log.info("Calling Blog API — title='{}', hasVideo={}", title,
                    videoUrl != null && !videoUrl.isBlank());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestPayload, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    BLOG_API, HttpMethod.PATCH, entity, String.class);

            log.info("Blog API Response: {}", response.getBody());
            return response.getStatusCode().is2xxSuccessful() ? "POSTED SUCCESSFULLY" : "FAILED";

        } catch (Exception ex) {
            log.error("Blog API Failed", ex);
            return "FAILED: " + ex.getMessage();
        }
    }

    /** Backward-compatible overload — no video */
    public String post(String title, String content, String imageUrl) {
        return post(title, content, imageUrl, null);
    }
}