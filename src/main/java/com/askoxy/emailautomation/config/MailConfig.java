package com.askoxy.emailautomation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class MailConfig {

    @Value("${useronboard.mail.host}")
    private String useronboardHost;

    @Value("${useronboard.mail.port}")
    private int useronboardPort;

    @Value("${useronboard.mail.username}")
    private String useronboardUsername;

    @Value("${useronboard.mail.password}")
    private String useronboardPassword;

    @Value("${radhaclone.mail.host}")
    private String radhacloneHost;

    @Value("${radhaclone.mail.port}")
    private int radhaclonePort;

    @Value("${radhaclone.mail.username}")
    private String radhacloneUsername;

    @Value("${radhaclone.mail.password}")
    private String radhaclonePassword;

    @Primary
    @Bean(name = "emailAutomationMailSender")
    public JavaMailSender emailAutomationMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(useronboardHost);
        mailSender.setPort(useronboardPort);
        mailSender.setUsername(useronboardUsername);
        mailSender.setPassword(useronboardPassword);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        return mailSender;
    }

    @Bean(name = "radhaAiMailSender")
    public JavaMailSender radhaAiMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(radhacloneHost);
        mailSender.setPort(radhaclonePort);
        mailSender.setUsername(radhacloneUsername);
        mailSender.setPassword(radhaclonePassword);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        return mailSender;
    }
}