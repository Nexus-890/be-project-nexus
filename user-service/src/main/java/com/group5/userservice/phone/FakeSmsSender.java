package com.group5.userservice.phone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class FakeSmsSender implements SmsSender {
    private static final Logger log = LoggerFactory.getLogger(FakeSmsSender.class);

    private final ObjectProvider<JavaMailSender> mail;

    public FakeSmsSender(ObjectProvider<JavaMailSender> mail) {
        this.mail = mail;
    }

    @Override
    public void send(String phone, String text) {
        log.info("Fake SMS to {}: {}", phone, text);
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("sms@nexus.local");
        message.setTo(phone.replace("+", "") + "@sms.nexus.local");
        message.setSubject("SMS to " + phone);
        message.setText(text);
        try {
            sender.send(message);
        } catch (MailException exception) {
            log.warn("Fake SMS to {} was not copied to Mailpit", phone, exception);
        }
    }
}
