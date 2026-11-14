package com.ecommerceagent.integration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationConnectionRepository.WebhookEndpoint;
import com.ecommerceagent.integration.WebhookInboxRepository.Delivery;
import com.ecommerceagent.integration.WebhookInboxRepository.InboxItem;
import com.ecommerceagent.integration.WebhookInboxRepository.InboxStatus;
import com.ecommerceagent.integration.WebhookRejectedException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WooCommerce webhooks (ADR-0009): the public receiving side, the webhook secret and the inbox processing.
 * The store always comes from the connection found by the endpoint key, never from the payload.
 */
@Service
public class WooWebhooks {

    /** System actor for receiving and normalizing webhooks (read scope, ADR-0004). */
    public static final String WEBHOOK_SERVICE = "woo-webhook";
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Topics to create in WooCommerce; anything else is acknowledged and dropped. */
    public static final List<String> TOPICS = List.of("order.created", "order.updated", "product.created",
            "product.updated");
    static final String WEBHOOK_SECRET = "woocommerce.webhook_secret";
    static final int MAX_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(WooWebhooks.class);
    private static final Set<String> ACCEPTED_TOPICS = Set.copyOf(TOPICS);
    private static final Pattern ENDPOINT_KEY = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PING = Pattern.compile("webhook_id=\\d{1,20}");
    private static final Pattern SECRET = Pattern.compile("[\\x21-\\x7E]{16,200}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final IntegrationConnectionRepository connections;
    private final IntegrationConnections integrationConnections;
    private final WebhookInboxRepository inbox;
    private final WooWebhookNormalizer normalizer;
    private final SecretCipher cipher;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String baseUrl;

    WooWebhooks(IntegrationConnectionRepository connections, IntegrationConnections integrationConnections,
            WebhookInboxRepository inbox, WooWebhookNormalizer normalizer, SecretCipher cipher,
            StoreAccess storeAccess, AuditTrail audit, TransactionTemplate transactions, Clock clock,
            IntegrationProperties properties) {
        this.connections = connections;
        this.integrationConnections = integrationConnections;
        this.inbox = inbox;
        this.normalizer = normalizer;
        this.cipher = cipher;
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
        String configured = properties.webhooks().baseUrl();
        this.baseUrl = configured == null || configured.isBlank() ? null : configured.strip().replaceAll("/+$", "");
    }

    /** The address to enter in WooCommerce, if the public API address is configured. */
    public Optional<String> deliveryUrl(IntegrationConnection connection) {
        return Optional.ofNullable(baseUrl)
                .map(base -> base + "/api/v1/webhooks/woocommerce/" + connection.webhookEndpointKey());
    }

    /**
     * Stores the shared secret that the user also entered in WooCommerce. The value comes from the browser
     * and is never returned (ADR-0009).
     */
    @Transactional
    public IntegrationConnection saveSecret(TrustedStoreScope scope, UUID connectionId, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new InvalidIntegrationRequestException("secret", "REQUIRED", "This field is required");
        }
        String value = secret.strip();
        if (!SECRET.matcher(value).matches()) {
            throw new InvalidIntegrationRequestException("secret", "SECRET_FORMAT",
                    "Use 16 to 200 printable characters without spaces");
        }
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        IntegrationConnection connection = integrationConnections.get(fresh, connectionId);
        if (connection.provider() != IntegrationProvider.WOOCOMMERCE) {
            throw new InvalidIntegrationRequestException("provider", "UNSUPPORTED", "Not a WooCommerce connection");
        }
        connections.saveCredential(fresh, connectionId, WEBHOOK_SECRET,
                cipher.seal(value, IntegrationConnections.associatedData(fresh, connectionId, WEBHOOK_SECRET)));
        connections.markWebhookSecretSet(fresh, connectionId);
        audit.record(AuditEntry.inScope(fresh, "INTEGRATION_WEBHOOK_SECRET_UPDATED", "integration_connection",
                connectionId.toString(), Map.of("provider", connection.provider().name())));
        return integrationConnections.get(fresh, connectionId);
    }

    /**
     * Verifies and stores one delivery. Order: size, endpoint, ping, signature over the raw bytes, topic and
     * body shape, then the inbox. Only a verified delivery is stored.
     *
     * @throws WebhookRejectedException for an unknown endpoint, a bad signature or an oversized body
     */
    @Transactional
    public WebhookReceipt receive(String endpointKey, WebhookHeaders headers, byte[] body) {
        if (body.length > MAX_BODY_BYTES) {
            throw new WebhookRejectedException(Reason.PAYLOAD_TOO_LARGE, "Webhook body too large");
        }
        if (endpointKey == null || !ENDPOINT_KEY.matcher(endpointKey).matches()) {
            throw new WebhookRejectedException(Reason.NOT_FOUND, "Unknown webhook endpoint");
        }
        WebhookEndpoint endpoint = connections.webhookEndpoint(endpointKey)
                .filter(found -> found.provider() == IntegrationProvider.WOOCOMMERCE)
                .orElseThrow(() -> new WebhookRejectedException(Reason.NOT_FOUND, "Unknown webhook endpoint"));
        if (isPing(headers, body)) {
            return WebhookReceipt.PING;
        }
        TrustedStoreScope scope = storeAccess
                .systemScope(WEBHOOK_SERVICE, endpoint.organizationId(), endpoint.storeId())
                .orElseThrow(() -> new WebhookRejectedException(Reason.NOT_FOUND, "Unknown webhook endpoint"));
        String secret = connections.credential(scope, endpoint.connectionId(), WEBHOOK_SECRET)
                .map(sealed -> cipher.open(sealed,
                        IntegrationConnections.associatedData(scope, endpoint.connectionId(), WEBHOOK_SECRET)))
                .orElseThrow(() -> new WebhookRejectedException(Reason.SIGNATURE_INVALID, "No webhook secret saved"));
        if (!signatureMatches(secret, headers.signature(), body)) {
            throw new WebhookRejectedException(Reason.SIGNATURE_INVALID, "Webhook signature does not match");
        }
        Instant now = clock.instant();
        connections.markWebhookReceived(scope, endpoint.connectionId(), now);
        String topic = headers.topic();
        if (topic == null || !ACCEPTED_TOPICS.contains(topic)) {
            return WebhookReceipt.IGNORED;
        }
        Optional<JsonNode> json = parseObject(body);
        if (json.isEmpty()) {
            return WebhookReceipt.IGNORED;
        }
        JsonNode minimized = topic.startsWith("order.")
                ? WooPayloads.minimizeOrder(json.get())
                : WooPayloads.minimizeProduct(json.get());
        boolean stored = inbox.insert(scope, endpoint.connectionId(),
                new Delivery(topic, limit(headers.webhookId()), limit(headers.deliveryId())), sha256(body),
                JSON.writeValueAsString(minimized), now);
        return stored ? WebhookReceipt.ACCEPTED : WebhookReceipt.DUPLICATE;
    }

    /**
     * Processes up to {@code limit} due inbox items, each in its own transaction with its state change and
     * outbox events (worker side). Returns the number of items handled.
     */
    public int processPending(int limit) {
        int handled = 0;
        for (; handled < limit; handled++) {
            AtomicReference<InboxItem> claimed = new AtomicReference<>();
            try {
                Boolean found = transactions.execute(status -> {
                    Optional<InboxItem> next = inbox.claimNext();
                    if (next.isEmpty()) {
                        return false;
                    }
                    InboxItem item = next.get();
                    claimed.set(item);
                    TrustedStoreScope scope = storeAccess
                            .systemScope(WEBHOOK_SERVICE, item.organizationId(), item.storeId())
                            .orElseThrow(() -> new IllegalStateException("Store of inbox item no longer exists"));
                    WooWebhookNormalizer.Result result = normalizer.apply(scope, item.id(), item.topic(),
                            item.payload());
                    inbox.finish(scope, item.id(), result.status(), result.outcome());
                    return true;
                });
                if (!Boolean.TRUE.equals(found)) {
                    break;
                }
            } catch (RuntimeException e) {
                InboxItem item = claimed.get();
                if (item == null) {
                    throw e;
                }
                fail(item, e);
            }
        }
        return handled;
    }

    private void fail(InboxItem item, RuntimeException e) {
        // An unreadable payload will not get better on retry.
        boolean permanent = e instanceof WooImportException || e instanceof JacksonException;
        Instant next = clock.instant().plus(Duration.ofSeconds(5L << Math.min(item.attempts(), 10)));
        String outcome = permanent ? "INVALID_PAYLOAD" : "ERROR:" + e.getClass().getSimpleName();
        log.warn("Webhook inbox item {} failed (attempt {}): {}", item.id(), item.attempts() + 1, outcome, e);
        transactions.executeWithoutResult(status ->
                inbox.recordFailure(item.id(), permanent ? 1 : MAX_ATTEMPTS, next, outcome));
    }

    private static boolean isPing(WebhookHeaders headers, byte[] body) {
        String contentType = headers.contentType() == null ? "" : headers.contentType().toLowerCase();
        return headers.signature() == null && contentType.startsWith("application/x-www-form-urlencoded")
                && body.length < 64 && PING.matcher(new String(body, StandardCharsets.US_ASCII)).matches();
    }

    static boolean signatureMatches(String secret, String signature, byte[] body) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        byte[] provided;
        try {
            provided = Base64.getDecoder().decode(signature.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmacSha256(secret, body), provided);
    }

    static byte[] hmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static Optional<JsonNode> parseObject(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() && node.path("id").isNumber() ? Optional.of(node) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String limit(String header) {
        return header == null ? null : header.substring(0, Math.min(header.length(), 100));
    }

    /** The delivery headers we read; values come from the shop and are untrusted. */
    public record WebhookHeaders(String contentType, String topic, String signature, String webhookId,
            String deliveryId) {
    }
}
