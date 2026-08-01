// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** The production transport's failure mapping and its bounded-body guard. */
class HttpTransportTest {
    @Test
    void mapsConnectionFailuresToTheFailureSentinel() {
        // Port 1 on loopback refuses immediately, so the transport sees a connect failure with no network.
        Transport.Result result = new HttpTransport().fetch(URI.create("http://127.0.0.1:1/"), Map.of());

        assertEquals(Transport.Result.FAILURE, result);
    }

    @Test
    void aBodyWithinTheCapIsReturnedVerbatim() {
        HttpTransport.BoundedBodySubscriber subscriber = new HttpTransport.BoundedBodySubscriber(16);
        subscriber.onSubscribe(noOpSubscription());
        subscriber.onNext(List.of(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8))));
        subscriber.onComplete();

        assertEquals("hello", subscriber.getBody().toCompletableFuture().join());
    }

    @Test
    void anOversizedBodyFailsInsteadOfBuffering() {
        HttpTransport.BoundedBodySubscriber subscriber = new HttpTransport.BoundedBodySubscriber(4);
        subscriber.onSubscribe(noOpSubscription());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5 })));

        CompletableFuture<String> body = subscriber.getBody().toCompletableFuture();
        assertTrue(body.isCompletedExceptionally(), "a body past the cap fails the exchange rather than buffering");
    }

    @Test
    void anOversizedBodyCancelsTheSubscription() {
        RecordingSubscription subscription = new RecordingSubscription();
        HttpTransport.BoundedBodySubscriber subscriber = new HttpTransport.BoundedBodySubscriber(4);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5 })));

        assertTrue(subscription.canceled, "overflow stops pulling further bytes from the network");
    }

    @Test
    void bytesAreSummedAcrossChunksNotCheckedPerChunk() {
        HttpTransport.BoundedBodySubscriber subscriber = new HttpTransport.BoundedBodySubscriber(6);
        subscriber.onSubscribe(noOpSubscription());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] { 4, 5, 6, 7 })));

        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally(),
                "two under-cap chunks that together exceed the cap still fail");
    }

    private static Flow.Subscription noOpSubscription() {
        return new Flow.Subscription() {
            @Override
            public void request(long count) {}

            @Override
            public void cancel() {}
        };
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private boolean canceled;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {
            this.canceled = true;
        }
    }
}
