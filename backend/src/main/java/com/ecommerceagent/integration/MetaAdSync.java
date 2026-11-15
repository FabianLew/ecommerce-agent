package com.ecommerceagent.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.ecommerceagent.commerce.AdData;
import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.commerce.AdSource;
import com.ecommerceagent.commerce.AdStatus;
import com.ecommerceagent.identity.SystemStoreRunner;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.meta.MetaAd;
import com.ecommerceagent.integration.meta.MetaApiException;
import com.ecommerceagent.integration.meta.MetaGraphClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Polls the ads of connected Meta accounts and upserts their state (ADR-0017 point 4). An ad that is not returned
 * keeps its last state and grows old, so the policy's freshness check blocks acting on it.
 */
@Service
public class MetaAdSync {

    public static final String SERVICE = "meta-sync";
    static final String NO_CREATIVE = "meta-creative:none";
    private static final Logger log = LoggerFactory.getLogger(MetaAdSync.class);
    private static final int BATCH = 20;

    private final MetaConnectionRepository repository;
    private final MetaConnections connections;
    private final MetaGraphClient client;
    private final AdRepository ads;
    private final SystemStoreRunner stores;
    private final MetaProperties properties;
    private final Clock clock;

    MetaAdSync(MetaConnectionRepository repository, MetaConnections connections, MetaGraphClient client,
            AdRepository ads, SystemStoreRunner stores, MetaProperties properties, Clock clock) {
        this.repository = repository;
        this.connections = connections;
        this.client = client;
        this.ads = ads;
        this.stores = stores;
        this.properties = properties;
        this.clock = clock;
    }

    /** Polls every connected account that is due; returns how many were polled. */
    public int syncDue() {
        AtomicInteger polled = new AtomicInteger();
        stores.forEachStore(SERVICE, scope -> {
            if (!repository.due(scope, clock.instant(), BATCH).isEmpty()) {
                sync(scope);
                polled.incrementAndGet();
            }
        });
        return polled.get();
    }

    /** Reads all ads of the store's connected account; returns the number upserted. */
    public int sync(TrustedStoreScope scope) {
        MetaConnection connection = repository.find(scope)
                .filter(found -> found.status() == MetaConnectionStatus.CONNECTED).orElse(null);
        if (connection == null) {
            return 0;
        }
        Instant started = clock.instant();
        try {
            List<MetaAd> read = client.ads(connections.token(scope, connection), connection.accountId());
            int upserted = 0;
            Instant observedAt = clock.instant();
            for (MetaAd ad : read) {
                Optional<AdData> data = adData(connection.accountId(), ad, observedAt);
                if (data.isPresent()) {
                    ads.upsertAd(scope, data.get());
                    upserted++;
                }
            }
            repository.recordSync(scope, connection.id(), observedAt, upserted, null, null);
            return upserted;
        } catch (MetaApiException e) {
            Duration wait = properties.minBackoff();
            if (e.kind() == MetaApiException.Kind.RATE_LIMITED && e.retryAfter() != null
                    && e.retryAfter().compareTo(wait) > 0) {
                wait = e.retryAfter();
            }
            if (e.kind() == MetaApiException.Kind.AUTH_FAILED || e.kind() == MetaApiException.Kind.PERMISSION_DENIED) {
                repository.markAuthFailed(scope, connection.id(), e.kind().name(), started);
            }
            repository.recordSync(scope, connection.id(), started, 0, e.kind().name(), started.plus(wait));
            log.warn("Meta ad poll for store {} failed: {}", scope.storeId(), e.kind());
            return 0;
        }
    }

    /** Maps a Graph API ad; an own status we do not know is skipped (and so grows old) rather than guessed. */
    static Optional<AdData> adData(String accountId, MetaAd ad, Instant observedAt) {
        AdStatus status;
        try {
            status = AdStatus.valueOf(ad.status());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        if (ad.id() == null || ad.id().isBlank()) {
            return Optional.empty();
        }
        String fingerprint = ad.creativeId() == null || ad.creativeId().isBlank() ? NO_CREATIVE
                : "meta-creative:" + ad.creativeId();
        String effective = ad.effectiveStatus() == null || ad.effectiveStatus().isBlank() ? status.name()
                : ad.effectiveStatus();
        String name = ad.name() == null || ad.name().isBlank() ? ad.id() : ad.name();
        return Optional.of(new AdData(ad.id(), "act_" + accountId, name, status, effective, fingerprint, observedAt,
                AdSource.META_API));
    }
}
