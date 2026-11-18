package com.group5.paymentservice.payout.evidence;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Reject oversized provider responses before an unbounded byte-array accumulation can occur. */
final class BoundedEvidenceBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final HttpResponse.BodySubscriber<byte[]> delegate =
            HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long received;
    private boolean failed;

    BoundedEvidenceBodySubscriber(int limit) {
        this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (failed) return;
        for (var buffer : buffers) received += buffer.remaining();
        if (received > limit) {
            failed = true;
            subscription.cancel();
            delegate.onError(new IOException("Private storage response exceeds its allowed size"));
        } else {
            delegate.onNext(buffers);
        }
    }

    @Override
    public void onError(Throwable error) {
        if (!failed) delegate.onError(error);
    }

    @Override
    public void onComplete() {
        if (!failed) delegate.onComplete();
    }
}
