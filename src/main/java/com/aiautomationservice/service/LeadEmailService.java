package com.aiautomationservice.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Properties;

@Slf4j
@Service
public class LeadEmailService {

    @Value("${app.gmail.client-id}")
    private String clientId;

    @Value("${app.gmail.client-secret}")
    private String clientSecret;

    @Value("${app.gmail.refresh-token}")
    private String refreshToken;

    @Value("${app.gmail.sender-address}")
    private String senderAddress;

    @Value("${app.gmail.sender-name:ASKOXY.AI TEAM}")
    private String senderName;

    // ── Main method called from LeadService ───────────────────────────────────
    public String sendToLead(String toLeadEmail, String subject, String htmlContent) {
        try {
            Gmail gmailService = buildGmailService();

            // Build MimeMessage with YOUR html directly — no wrapping
            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage mimeMessage = new MimeMessage(session);
            mimeMessage.setFrom(new InternetAddress(senderAddress, senderName));
            mimeMessage.setRecipients(MimeMessage.RecipientType.TO,
                    InternetAddress.parse(toLeadEmail));
            mimeMessage.setSubject(subject != null ? subject : "Thank you for enrolling!", "UTF-8");

            MimeMultipart multipart = new MimeMultipart("alternative");

            // Plain text fallback
            MimeBodyPart plainPart = new MimeBodyPart();
            plainPart.setText("Thank you for your enquiry with AskOxy Study Abroad!", "UTF-8", "plain");

            // Your full HTML — sent as-is, no wrapping
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setText(htmlContent, "UTF-8", "html");

            multipart.addBodyPart(plainPart);
            multipart.addBodyPart(htmlPart);
            mimeMessage.setContent(multipart);
            mimeMessage.saveChanges();

            // Encode and send
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            mimeMessage.writeTo(buffer);
            String encodedEmail = Base64.getUrlEncoder()
                    .encodeToString(buffer.toByteArray());

            Message gmailMessage = new Message();
            gmailMessage.setRaw(encodedEmail);

            Message sent = gmailService.users()
                    .messages()
                    .send("me", gmailMessage)
                    .execute();

            log.info("[LeadEmailService] ✅ Email sent to lead: {} | gmailId={}",
                    toLeadEmail, sent.getId());
            return "SENT";

        } catch (Exception ex) {
            log.error("[LeadEmailService] ❌ Failed to send to {}: {}",
                    toLeadEmail, ex.getMessage());
            return "FAILED: " + ex.getMessage();
        }
    }

    // ── Gmail OAuth2 service builder — same as Rishi's ────────────────────────
    @SuppressWarnings("deprecation")
    private Gmail buildGmailService() throws Exception {
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(GoogleNetHttpTransport.newTrustedTransport())
                .setJsonFactory(GsonFactory.getDefaultInstance())
                .setClientSecrets(clientId, clientSecret)
                .build()
                .setRefreshToken(refreshToken);

        credential.refreshToken();

        return new Gmail.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("AskOxy-MailAutomation")
                .build();
    }
}