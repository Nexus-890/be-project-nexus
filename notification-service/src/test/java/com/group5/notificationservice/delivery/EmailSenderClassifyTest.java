package com.group5.notificationservice.delivery;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import com.group5.notificationservice.shared.config.DeliveryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.ConnectException;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailSenderClassifyTest {

    @Test
    void receiptUsesHtmlMimeWhileExistingNotificationsRemainPlainText() throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        MimeMessage receipt = new MimeMessage(Session.getInstance(new Properties()));
        MimeMessage plain = new MimeMessage(Session.getInstance(new Properties()));
        when(mail.createMimeMessage()).thenReturn(receipt, plain);
        EmailSender sender = new EmailSender(mail, new DeliveryProperties());

        String html = "<!doctype html><html><body><h1>Receipt</h1><p>Cà phê &amp; tea</p></body></html>";
        sender.send("buyer@example.com", "Order receipt", html, true);
        sender.send("buyer@example.com", "Order placed", "Your order NX-204 was placed");
        receipt.saveChanges();
        plain.saveChanges();

        assertThat(receipt.isMimeType("text/html")).isTrue();
        assertThat(receipt.getContent()).isEqualTo(html);
        assertThat(plain.isMimeType("text/plain")).isTrue();
        assertThat(plain.getContent()).isEqualTo("Your order NX-204 was placed");
        verify(mail).send(receipt);
        verify(mail).send(plain);
    }

    @Test
    void refusedConnectionIsATemporaryFailure() {
        MessagingException connect = new MessagingException("Couldn't connect to host", new ConnectException("refused"));
        DeliveryFailure failure = EmailSender.classify(new MailSendException("Mail server connection failed", connect));

        assertThat(failure.errorCode()).isEqualTo("SMTP_UNAVAILABLE");
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    void rejectedAddressIsPermanent() throws Exception {
        SendFailedException rejected = new SendFailedException("550 no such user", null, new InternetAddress[0],
                new InternetAddress[0], new InternetAddress[] {new InternetAddress("buyer@example.com")});
        DeliveryFailure failure = EmailSender.classify(
                new MailSendException(Map.of(new Object(), rejected)));

        assertThat(failure.errorCode()).isEqualTo("EMAIL_ADDRESS_REJECTED");
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    void otherSendErrorsAreRetried() {
        DeliveryFailure failure = EmailSender.classify(
                new MailSendException("451 try later", new MessagingException("451 try later")));

        assertThat(failure.errorCode()).isEqualTo("SMTP_SEND_FAILED");
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    void emailAddressesAreMaskedBeforeLogging() {
        assertThat(Emails.mask("550 buyer.one@gmail.com rejected")).isEqualTo("550 b***@gmail.com rejected");
        assertThat(Emails.mask("no address here")).isEqualTo("no address here");
        assertThat(Emails.mask(null)).isNull();
    }
}
