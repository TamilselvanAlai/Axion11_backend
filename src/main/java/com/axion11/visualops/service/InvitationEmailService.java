package com.axion11.visualops.service;

import com.axion11.visualops.models.User;
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
public class InvitationEmailService {

    private static final Logger log = LoggerFactory.getLogger(InvitationEmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Async
    public void sendInviteEmail(User user, String token) {
        try {
            String link = frontendUrl + "/set-password?token=" + token;
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(user.getEmail());
            mail.setFrom(fromAddress);
            mail.setSubject("You're invited to Axion11 VisualOps");
            mail.setText(buildBody(user, link));
            mailSender.send(mail);
            log.info("Invitation email sent to {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send invitation email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    private String buildBody(User user, String link) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(user.getName() != null ? user.getName() : user.getEmail()).append(",\n\n");
        sb.append("You've been added to Axion11 VisualOps. Set your password to activate your account:\n\n");
        sb.append(link).append("\n\n");
        sb.append("This link expires in 48 hours. If you didn't expect this invite, you can ignore this email.\n");
        return sb.toString();
    }
}
