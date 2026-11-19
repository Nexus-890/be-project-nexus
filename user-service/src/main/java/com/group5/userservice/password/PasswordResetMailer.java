package com.group5.userservice.password;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PasswordResetMailer {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final PasswordResetProperties properties;

    public PasswordResetMailer(ObjectProvider<JavaMailSender> mail, PasswordResetProperties properties) {
        this.mail = mail;
        this.properties = properties;
    }

    public boolean send(String email, String fullName, String token) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) {
            log.warn("Password reset mail is not configured; no link was sent");
            return false;
        }
        String link = UriComponentsBuilder.fromUriString(properties.getWebBaseUrl())
                .path("/reset-password").queryParam("token", token).build().toUriString();
        long minutes = properties.getLinkLifetime().toMinutes();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getMailFrom());
        message.setTo(email);
        message.setSubject("Reset your Nexus password");
        message.setText("""
                Hi %s,

                We received a request to reset the password of your Nexus account.
                Open this link to choose a new password. It works once and expires in %d minutes:

                %s

                If you did not ask for this, ignore this email; your password stays the same.
                """.formatted(fullName, minutes, link));
        try {
            sender.send(message);
            return true;
        } catch (MailException exception) {
            log.error("Password reset mail could not be sent", exception);
            return false;
        }
    }
}
