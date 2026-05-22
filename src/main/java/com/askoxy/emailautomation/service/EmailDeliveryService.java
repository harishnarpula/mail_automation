package com.askoxy.emailautomation.service;

import com.askoxy.emailautomation.dto.GeneratedEmailDto;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailDeliveryService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.email.from-name}")
    private String fromName;

    public String send(String toEmail, GeneratedEmailDto email) {
        return send(toEmail, email, null, null);
    }

    public String send(String toEmail, GeneratedEmailDto email, String inReplyTo, String references) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(fromEmail, fromName);
            helper.setTo(toEmail);
            helper.setSubject(email.getSubject());
            helper.setText(email.getBody(), false);

            if (inReplyTo != null && !inReplyTo.isBlank()) {
                message.setHeader("In-Reply-To", inReplyTo.trim());
            }
            if (references != null && !references.isBlank()) {
                message.setHeader("References", references.trim());
            }

            // Ensure Message-ID is generated before send so we can persist it.
            message.saveChanges();
            String sentMessageId = message.getMessageID();

            mailSender.send(message);

            if (sentMessageId == null || sentMessageId.isBlank()) {
                sentMessageId = message.getMessageID();
            }

            log.info("Email sent to {} messageId={} inReplyTo={}", toEmail, sentMessageId, inReplyTo);
            return sentMessageId;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to send email to " + toEmail, ex);
        }
    }
}
