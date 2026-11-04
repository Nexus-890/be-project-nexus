package com.group5.notificationservice.delivery;

import com.group5.notificationservice.shared.config.DeliveryProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "nexus.notification", name = "email-enabled", havingValue = "true")
public class EmailSender {

    private static final int MAX_MESSAGE_ID = 120;

    private final JavaMailSender mailSender;
    private final DeliveryProperties properties;

    public EmailSender(JavaMailSender mailSender, DeliveryProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public String send(String to, String subject, String text) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.getFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text);
            mailSender.send(message);
            String messageId = message.getMessageID();
            return messageId == null ? null : messageId.substring(0, Math.min(messageId.length(), MAX_MESSAGE_ID));
        } catch (MessagingException | MailParseException | MailPreparationException exception) {
            throw new DeliveryFailure("EMAIL_INVALID", false, exception);
        } catch (MailAuthenticationException exception) {
            throw new DeliveryFailure("SMTP_AUTH_FAILED", false, exception);
        } catch (MailSendException exception) {
            throw classify(exception);
        } catch (MailException exception) {
            throw new DeliveryFailure("SMTP_SEND_FAILED", true, exception);
        }
    }

    // SMTP TỪ CHỐI ĐỊA CHỈ -> LỖI VĨNH VIỄN, KHÔNG GỬI LẠI
    // KHÔNG KẾT NỐI ĐƯỢC SMTP / TIMEOUT -> LỖI TẠM THỜI, GỬI LẠI
    static DeliveryFailure classify(MailSendException exception) {
        List<Throwable> causes = causes(exception);
        for (Throwable cause : causes) {
            if (cause instanceof SendFailedException failed
                    && failed.getInvalidAddresses() != null && failed.getInvalidAddresses().length > 0) {
                return new DeliveryFailure("EMAIL_ADDRESS_REJECTED", false, exception);
            }
        }
        for (Throwable cause : causes) {
            if (cause instanceof IOException) {
                return new DeliveryFailure("SMTP_UNAVAILABLE", true, exception);
            }
        }
        return new DeliveryFailure("SMTP_SEND_FAILED", true, exception);
    }

    private static List<Throwable> causes(MailSendException exception) {
        List<Throwable> roots = new ArrayList<>();
        roots.add(exception);
        roots.addAll(exception.getFailedMessages().values());
        List<Throwable> all = new ArrayList<>();
        for (Throwable root : roots) {
            for (Throwable cause = root; cause != null && !all.contains(cause); cause = next(cause)) {
                all.add(cause);
            }
        }
        return all;
    }

    private static Throwable next(Throwable cause) {
        if (cause instanceof MessagingException messaging && messaging.getNextException() != null) {
            return messaging.getNextException();
        }
        return cause.getCause();
    }
}
