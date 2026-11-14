package com.ecommerceagent.integration.woocommerce;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * One-legged OAuth 1.0a with HMAC-SHA256 as verified by WooCommerce for plain-HTTP stores (ADR-0007).
 *
 * <p>Mirrors WC_REST_Authentication::check_oauth_signature: keys sorted by byte order, each key and value
 * RFC 3986-encoded, each {@code key=value} pair encoded again and the pairs joined with {@code %26}, which
 * equals the RFC 5849 base string. Verified against the local store, including values with reserved
 * characters such as the colons of an ISO date (WooCommerceLiveTest).
 */
final class OAuth1Signer {

    static final String SIGNATURE_METHOD = "HMAC-SHA256";

    private OAuth1Signer() {
    }

    /** Returns the {@code oauth_signature} for a request whose query holds all API and oauth_* parameters. */
    static String sign(String method, URI urlWithoutQuery, Map<String, String> parameters, String consumerSecret) {
        String parameterString = new TreeMap<>(parameters).entrySet().stream()
                .map(e -> encode(encode(e.getKey()) + "=" + encode(e.getValue())))
                .collect(Collectors.joining("%26"));
        String base = method.toUpperCase() + "&" + encode(urlWithoutQuery.toString()) + "&" + parameterString;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((consumerSecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** RFC 3986 percent-encoding, equivalent to PHP {@code rawurlencode}. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
