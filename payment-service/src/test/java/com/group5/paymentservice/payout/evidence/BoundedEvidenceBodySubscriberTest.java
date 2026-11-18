package com.group5.paymentservice.payout.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;

class BoundedEvidenceBodySubscriberTest {
    @Test
    void acceptsAnExactLimitResponseAcrossChunks() {
        var subscriber = new BoundedEvidenceBodySubscriber(3);
        var subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {3})));
        subscriber.onComplete();
        assertThat(subscriber.getBody().toCompletableFuture().join())
                .containsExactly((byte) 1, (byte) 2, (byte) 3);
        verify(subscription, never()).cancel();
    }

    @Test
    void cancelsTheResponseBeforeAccumulatingOversizedProviderData() {
        var subscriber = new BoundedEvidenceBodySubscriber(3);
        var subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {3, 4})));
        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasRootCauseInstanceOf(java.io.IOException.class);
    }
}
