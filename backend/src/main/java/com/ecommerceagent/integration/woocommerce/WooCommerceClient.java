package com.ecommerceagent.integration.woocommerce;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

/**
 * Read-only client for the WooCommerce REST API v3 (ADR-0007). Paths are fixed by callers; the client
 * checks the address policy before every request, never follows redirects and caps the response size.
 *
 * <p>{@code responseTimeout} is a deadline for the whole response, headers and body (FIX-01). The body is
 * received asynchronously into a size-limited buffer; when the deadline passes, the exchange is cancelled,
 * which closes the connection, so no thread stays blocked on a store that stopped sending.
 */
public final class WooCommerceClient {

    private static final String API_PREFIX = "/wp-json/wc/v3/";

    private final StoreUrlPolicy policy;
    private final HttpClient http;
    private final Duration responseTimeout;
    private final int maxResponseBytes;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WooCommerceClient(StoreUrlPolicy policy, Duration connectTimeout, Duration responseTimeout,
            int maxResponseBytes, Clock clock) {
        this.policy = policy;
        this.responseTimeout = responseTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.clock = clock;
        // No proxy: the address policy must judge the real destination. An egress proxy comes with F4.
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    public StoreUrlPolicy policy() {
        return policy;
    }

    /** {@code GET {storeUrl}/wp-json/wc/v3/{resource}?{query}}. */
    public WooResponse get(URI storeUrl, WooCredentials credentials, String resource, Map<String, String> query)
            throws WooClientException {
        policy.requireAllowedAddress(storeUrl);
        URI endpoint = URI.create(storeUrl + API_PREFIX + resource);
        Map<String, String> parameters = new LinkedHashMap<>(query);
        HttpRequest.Builder request = HttpRequest.newBuilder().GET().timeout(responseTimeout)
                .header("Accept", "application/json");

        if ("https".equals(storeUrl.getScheme())) {
            String basic = credentials.consumerKey() + ":" + credentials.consumerSecret();
            request.header("Authorization", "Basic "
                    + java.util.Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        } else {
            parameters.put("oauth_consumer_key", credentials.consumerKey());
            parameters.put("oauth_nonce", nonce());
            parameters.put("oauth_signature_method", OAuth1Signer.SIGNATURE_METHOD);
            parameters.put("oauth_timestamp", Long.toString(clock.instant().getEpochSecond()));
            parameters.put("oauth_signature",
                    OAuth1Signer.sign("GET", endpoint, parameters, credentials.consumerSecret()));
        }
        String queryString = parameters.entrySet().stream()
                .map(e -> OAuth1Signer.encode(e.getKey()) + "=" + OAuth1Signer.encode(e.getValue()))
                .collect(Collectors.joining("&"));
        request.uri(URI.create(queryString.isEmpty() ? endpoint.toString() : endpoint + "?" + queryString));

        HttpResponse<String> response = await(http.sendAsync(request.build(),
                info -> new LimitedBody(maxResponseBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1))));
        return new WooResponse(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null),
                response.body(),
                header(response, "X-WP-Total"),
                header(response, "X-WP-TotalPages"));
    }

    /**
     * Waits for the complete response until the deadline. On timeout or interruption the exchange is
     * cancelled: the JDK client then aborts the request and closes its connection.
     */
    private HttpResponse<String> await(CompletableFuture<HttpResponse<String>> exchange) throws WooClientException {
        try {
            return exchange.get(responseTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new WooClientException(WooClientException.Kind.TIMEOUT, "Store did not respond in time", e);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new WooClientException(WooClientException.Kind.UNREACHABLE, "Interrupted", e);
        } catch (ExecutionException e) {
            throw classify(e.getCause());
        }
    }

    private static WooClientException classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResponseTooLargeException) {
                return new WooClientException(WooClientException.Kind.RESPONSE_TOO_LARGE, "Response too large", null);
            }
            if (cause instanceof SSLException) {
                return new WooClientException(WooClientException.Kind.TLS_ERROR, "TLS handshake failed", failure);
            }
            if (cause instanceof HttpTimeoutException) {
                return new WooClientException(WooClientException.Kind.TIMEOUT, "Store did not respond in time", failure);
            }
            if (cause instanceof ConnectException) {
                break;
            }
        }
        return new WooClientException(WooClientException.Kind.UNREACHABLE, "Store not reachable", failure);
    }

    private static OptionalLong header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name)
                .map(String::strip)
                .filter(v -> v.matches("\\d{1,18}"))
                .map(v -> OptionalLong.of(Long.parseLong(v)))
                .orElse(OptionalLong.empty());
    }

    private String nonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Collects the body up to {@code maxBytes}; a larger body (declared or actual) cancels the subscription,
     * which aborts the exchange instead of reading the rest.
     */
    static final class LimitedBody implements HttpResponse.BodySubscriber<String> {

        private final int maxBytes;
        private final long declaredLength;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedBody(int maxBytes, long declaredLength) {
            this.maxBytes = maxBytes;
            this.declaredLength = declaredLength;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declaredLength > maxBytes) {
                tooLarge();
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (buffer.size() + (long) item.remaining() > maxBytes) {
                    tooLarge();
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toString(StandardCharsets.UTF_8));
        }

        private void tooLarge() {
            subscription.cancel();
            result.completeExceptionally(new ResponseTooLargeException());
        }
    }

    static final class ResponseTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        ResponseTooLargeException() {
            super("Response too large");
        }
    }
}
