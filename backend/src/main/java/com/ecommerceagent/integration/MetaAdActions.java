package com.ecommerceagent.integration;

import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.meta.MetaAd;
import com.ecommerceagent.integration.meta.MetaApiException;
import com.ecommerceagent.integration.meta.MetaGraphClient;
import org.springframework.stereotype.Service;

/**
 * The executor's view of one Meta ad (ADR-0020 point 6): a fresh read and the single pause write. The token stays in
 * this module; callers pass the trusted scope and the ad's external id, never credentials.
 */
@Service
public class MetaAdActions {

    /** What Meta says about the ad now. */
    public record AdState(String externalId, String status, String effectiveStatus, String creativeFingerprint) {
    }

    /** A read: the state, or the error kind when there is none. */
    public record Read(AdState state, String errorCode) {

        public boolean ok() {
            return state != null;
        }
    }

    public enum WriteOutcome {
        /** Meta confirmed the request; only a read verifies the effect. */
        ACCEPTED,
        /** The request may have been applied (timeout, connection loss or a server error after sending). */
        UNCERTAIN,
        /** Meta refused the request; nothing changed. */
        REJECTED
    }

    public record Write(WriteOutcome outcome, String code) {
    }

    private final MetaConnections connections;
    private final MetaGraphClient client;

    MetaAdActions(MetaConnections connections, MetaGraphClient client) {
        this.connections = connections;
        this.client = client;
    }

    public Read read(TrustedStoreScope scope, String externalAdId) {
        try {
            MetaConnection connection = connections.find(scope)
                    .orElseThrow(() -> new IntegrationNotFoundException(null));
            MetaAd ad = client.ad(connections.token(scope, connection), externalAdId);
            String fingerprint = ad.creativeId() == null || ad.creativeId().isBlank() ? MetaAdSync.NO_CREATIVE
                    : "meta-creative:" + ad.creativeId();
            return new Read(new AdState(ad.id(), ad.status(), ad.effectiveStatus(), fingerprint), null);
        } catch (MetaApiException e) {
            return new Read(null, "META_" + e.kind().name());
        } catch (IntegrationNotFoundException | InvalidIntegrationRequestException e) {
            return new Read(null, "META_NOT_CONNECTED");
        }
    }

    /** Sends {@code status=PAUSED} once. The caller has recorded the intent before calling. */
    public Write pause(TrustedStoreScope scope, String externalAdId) {
        String token;
        try {
            MetaConnection connection = connections.find(scope)
                    .orElseThrow(() -> new IntegrationNotFoundException(null));
            token = connections.token(scope, connection);
        } catch (IntegrationNotFoundException | InvalidIntegrationRequestException e) {
            return new Write(WriteOutcome.REJECTED, "META_NOT_CONNECTED");
        }
        try {
            client.pause(token, externalAdId);
            return new Write(WriteOutcome.ACCEPTED, null);
        } catch (MetaApiException e) {
            return switch (e.kind()) {
                case UNCERTAIN -> new Write(WriteOutcome.UNCERTAIN, "META_UNCERTAIN");
                // A response that cannot be read came back after the request was handled.
                case UNEXPECTED_RESPONSE -> new Write(WriteOutcome.UNCERTAIN, "META_UNEXPECTED_RESPONSE");
                default -> new Write(WriteOutcome.REJECTED, "META_" + e.kind().name());
            };
        }
    }
}
