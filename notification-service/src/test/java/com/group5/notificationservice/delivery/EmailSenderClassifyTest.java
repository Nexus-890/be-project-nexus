package com.group5.notificationservice.delivery;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSenderClassifyTest {

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
