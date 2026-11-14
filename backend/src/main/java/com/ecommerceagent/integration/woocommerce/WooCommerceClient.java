package com.ecommerceagent.integration.woocommerce;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

/**
 * Read-only client for the WooCommerce REST API v3 (ADR-0007). Paths are fixed by callers; the client
 * checks the address policy before every request, never follows redirects and caps the response size.
 */
public final class WooCommerceClient {

    private static final String API_PREFIX = "/wp-json/wc/v3/";

    private final StoreUrlPolicy policy;
    private final HttpClient http;
    private final Duration readTimeout;
    private final int maxResponseBytes;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WooCommerceClient(StoreUrlPolicy policy, Duration connectTimeout, Duration readTimeout,
            int maxResponseBytes, Clock clock) {
        this.policy = policy;
        this.readTimeout = readTimeout;
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
        HttpRequest.Builder request = HttpRequest.newBuilder().GET().timeout(readTimeout)
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

        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body = read(response.body());
            return new WooResponse(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    body,
                    header(response, "X-WP-Total"),
                    header(response, "X-WP-TotalPages"));
        } catch (SSLException e) {
            throw new WooClientException(WooClientException.Kind.TLS_ERROR, "TLS handshake failed", e);
        } catch (HttpTimeoutException | ConnectException e) {
            throw new WooClientException(WooClientException.Kind.UNREACHABLE, "Store not reachable", e);
        } catch (IOException e) {
            if (e.getCause() instanceof SSLException) {
                throw new WooClientException(WooClientException.Kind.TLS_ERROR, "TLS handshake failed", e);
            }
            throw new WooClientException(WooClientException.Kind.UNREACHABLE, "Store not reachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WooClientException(WooClientException.Kind.UNREACHABLE, "Interrupted", e);
        }
    }

    private String read(InputStream body) throws IOException, WooClientException {
        try (body) {
            byte[] bytes = body.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw new WooClientException(WooClientException.Kind.RESPONSE_TOO_LARGE, "Response too large", null);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
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
}
