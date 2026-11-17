package com.group5.acceptance;

import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Completion-to-email acceptance across the real commerce and notification service boundaries. */
class ReceiptEmailAcceptanceTest {
    @Test void deliveredAndHeldOrdersNeverQueueOrSendReceiptEmail() {
        try (var commerce = new CommerceReceiptFixture(); var notification = new NotificationReceiptFixture()) {
            var order = commerce.order();
            assertThat(commerce.status(order)).isEqualTo("DELIVERED");
            assertThat(commerce.job.runOnce()).isZero();
            assertThat(commerce.events(order.id())).isEmpty();
            commerce.events(order.id()).forEach(notification::receive);
            assertNoReceipt(notification);

            commerce.completions.reportIssue(order.buyer(), order.id(), "Item has not arrived");
            commerce.due(order);
            assertThat(commerce.job.runOnce()).isZero();
            assertThatThrownBy(() -> commerce.completions.confirm(order.buyer(), order.id())).isInstanceOf(DomainException.class);
            assertThat(commerce.status(order)).isEqualTo("DELIVERED");
            assertThat(commerce.events(order.id())).isEmpty();
            commerce.events(order.id()).forEach(notification::receive);
            assertNoReceipt(notification);
        }
    }

    @Test void buyerConfirmationSendsExactlyOneHtmlReceiptDespiteEventReplayAndSmtpRetry() throws Exception {
        try (var commerce = new CommerceReceiptFixture(); var notification = new NotificationReceiptFixture()) {
            var order = commerce.order();
            assertThat(commerce.events(order.id())).isEmpty(); assertNoReceipt(notification);
            commerce.completions.confirm(order.buyer(), order.id());
            assertThatThrownBy(() -> commerce.completions.confirm(order.buyer(), order.id()))
                    .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("COMMERCE_ORDER_NOT_DELIVERED");
            assertThat(commerce.status(order)).isEqualTo("COMPLETED");
            assertThat(commerce.events(order.id())).hasSize(1);
            var envelope = commerce.events(order.id()).getFirst();
            String html = envelope.payload().path("receiptHtml").asString();
            assertThat(html).contains(order.number(), "Cà phê &lt;script&gt;private&lt;/script&gt; &amp; tea", "300000.00")
                    .doesNotContain("<script>");
            notification.receive(envelope); notification.receive(envelope);
            assertThat(notification.jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isEqualTo(1);
            assertThat(notification.inbox.messages()).isEmpty();

            int unavailablePort;
            try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { unavailablePort = socket.getLocalPort(); }
            notification.mail.setPort(unavailablePort);
            assertThat(notification.job.runOnce()).isEqualTo(1);
            assertThat(notification.inbox.messages()).isEmpty();
            assertThat(notification.jdbc.queryForObject("SELECT status::text FROM notification_deliveries", String.class)).isEqualTo("PENDING");
            notification.mail.setPort(notification.inbox.port());
            notification.jdbc.update("UPDATE notification_deliveries SET next_attempt_at=now()-interval '1 second'");
            notification.receive(envelope);
            assertThat(notification.job.runOnce()).isEqualTo(1);
            assertThat(notification.inbox.messages()).hasSize(1);
            var message = notification.inbox.messages().getFirst();
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("buyer-" + order.buyerId() + "@example.test");
            assertThat(message.getSubject()).isEqualTo("Order receipt");
            assertThat(message.isMimeType("text/html")).isTrue();
            // SMTP canonicalizes text line endings to CRLF; preserve every other HTML character.
            assertThat(message.getContent().toString().replace("\r\n", "\n").stripTrailing())
                    .isEqualTo(html.replace("\r\n", "\n").stripTrailing());
            assertThat(notification.jdbc.queryForList("SELECT status::text FROM notification_delivery_attempts ORDER BY attempt_number", String.class))
                    .containsExactly("FAILED", "SUCCEEDED");
            assertThat(notification.jdbc.queryForObject("SELECT count(*) FROM event_logs WHERE event_type='NotificationSent'", Long.class)).isEqualTo(1);
            notification.receive(envelope);
            assertThat(notification.job.runOnce()).isZero();
            assertThat(notification.inbox.messages()).hasSize(1);
        }
    }

    @Test void automaticCompletionSendsOneReceiptOnlyAfterThePersistedDeadline() throws Exception {
        try (var commerce = new CommerceReceiptFixture(); var notification = new NotificationReceiptFixture()) {
            var order = commerce.order();
            assertThat(commerce.job.runOnce()).isZero(); assertNoReceipt(notification);
            commerce.due(order);
            assertThat(commerce.job.runOnce()).isEqualTo(1);
            assertThat(commerce.job.runOnce()).isZero();
            assertThatThrownBy(() -> commerce.completions.confirm(order.buyer(), order.id()))
                    .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("COMMERCE_ORDER_NOT_DELIVERED");
            assertThat(commerce.status(order)).isEqualTo("COMPLETED");
            assertThat(commerce.events(order.id())).hasSize(1);
            assertThat(commerce.jdbc.queryForObject("SELECT reason FROM order_status_history WHERE order_id=? AND new_status='COMPLETED'", String.class, order.id()))
                    .isEqualTo("Completed automatically after delivery");
            var envelope = commerce.events(order.id()).getFirst();
            notification.receive(envelope); notification.receive(envelope);
            assertThat(notification.job.runOnce()).isEqualTo(1);
            assertThat(notification.inbox.messages()).hasSize(1);
            assertThat(notification.inbox.messages().getFirst().getContent().toString()).contains(order.number());
            assertThat(notification.job.runOnce()).isZero();
            assertThat(notification.jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(1);
        }
    }

    private static void assertNoReceipt(NotificationReceiptFixture notification) {
        assertThat(notification.job.runOnce()).isZero();
        assertThat(notification.jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        assertThat(notification.jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isZero();
        assertThat(notification.inbox.messages()).isEmpty();
    }
}
