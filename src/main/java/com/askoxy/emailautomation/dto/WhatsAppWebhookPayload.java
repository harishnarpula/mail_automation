package com.askoxy.emailautomation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps the incoming UltraMsg webhook POST body.
 * UltraMsg sends: { "data": { "body": "...", "from": "...", "type": "chat" }, "event_type": "message_received" }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhatsAppWebhookPayload {

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("data")
    private MessageData data;



    public String getEventType() { return eventType; }
    public MessageData getData() { return data; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageData {

        @JsonProperty("body")
        private String body; // admin's reply text

        @JsonProperty("from")
        private String from; // sender's WhatsApp number

        @JsonProperty("type")
        private String type; // "chat" for text messages

        public String getBody() { return body; }
        public String getFrom() { return from; }
        public String getType() { return type; }
    }
}