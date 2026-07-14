package com.axion11.visualops.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ErrorNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ErrorNotificationService.class);

    private final JavaMailSender mailSender;

    @Value("${error.notification.to}")
    private String notifyTo;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Async
    public void sendErrorEmail(String severity, String type, String message,
                               String rootCause, String suggestedFix,
                               String url, String stack) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(notifyTo);
            mail.setFrom(fromAddress);
            mail.setSubject("[Axion11] " + severity.toUpperCase() + " — " + truncate(message, 80));
            mail.setText(buildBody(severity, type, message, rootCause, suggestedFix, url, stack));
            mailSender.send(mail);
            log.info("Error notification email sent to {}", notifyTo);
        } catch (Exception e) {
            log.error("Failed to send error notification email: {}", e.getMessage());
        }
    }

    private String buildBody(String severity, String type, String message,
                             String rootCause, String suggestedFix,
                             String url, String stack) {
        StringBuilder sb = new StringBuilder();
        sb.append("AXION11 VISUALOPS — ERROR NOTIFICATION\n");
        sb.append("=======================================\n\n");
        sb.append("Severity : ").append(severity.toUpperCase()).append("\n");
        sb.append("Type     : ").append(type).append("\n");
        if (url != null && !url.isEmpty()) {
            sb.append("URL      : ").append(url).append("\n");
        }
        sb.append("\n--- Error Message ---\n");
        sb.append(message).append("\n");
        if (rootCause != null && !rootCause.isEmpty()) {
            sb.append("\n--- Root Cause ---\n");
            sb.append(rootCause).append("\n");
        }
        if (suggestedFix != null && !suggestedFix.isEmpty()) {
            sb.append("\n--- Suggested Fix ---\n");
            sb.append(suggestedFix).append("\n");
        }
        if (stack != null && !stack.isEmpty()) {
            sb.append("\n--- Stack Trace ---\n");
            sb.append(stack).append("\n");
        }
        sb.append("\n---------------------------------------\n");
        sb.append("This is an automated alert from the Axion11 Error Checker Agent.\n");
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
