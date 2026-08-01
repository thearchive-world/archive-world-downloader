// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;

/**
 * The production {@link Transport}: short timeouts, no redirects, a size-capped body, and every exception mapped to the
 * failure sentinel so a dead network can never surface past the seam.
 */
public final class HttpTransport implements Transport {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    /** The update check reads a short version list; a body past this is a hostile endpoint, not a real reply. */
    private static final int MAX_BODY_BYTES = 1 << 20;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Result fetch(URI uri, Map<String, String> headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET().timeout(TIMEOUT);
            headers.forEach(request::header);
            HttpResponse<String> response = client.send(request.build(),
                    responseInfo -> new BoundedBodySubscriber(MAX_BODY_BYTES));
            return new Result(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILURE;
        } catch (IOException | RuntimeException e) {
            return Result.FAILURE;
        }
    }

    /**
     * Buffers the response as a UTF-8 string but cancels the exchange and fails once the body exceeds {@code capacity}
     * bytes, so a hostile endpoint cannot force an unbounded allocation on the check thread.
     */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers
                .ofString(StandardCharsets.UTF_8);
        private final int capacity;
        private int received;
        private Flow.@Nullable Subscription subscription;

        BoundedBodySubscriber(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public CompletionStage<String> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                received += buffer.remaining();
            }
            if (received > capacity) {
                if (subscription != null) {
                    subscription.cancel();
                }
                delegate.onError(new IOException("update-check body exceeds " + capacity + " bytes"));
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
