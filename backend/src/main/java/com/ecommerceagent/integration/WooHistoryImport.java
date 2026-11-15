package com.ecommerceagent.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.woocommerce.WooResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Order history before the initial import's window, read backward in chunks in the background (B-13b,
 * ADR-0015). It has its own watermark, extends the order coverage only at its start, and emits no events, so it
 * never changes what the Inventory Guardian or Integration Health see.
 */
@Service
public class WooHistoryImport {

    /** How far back the history goes, from the initial import's window end (store time). */
    public static final int HISTORY_MONTHS = 12;
    /** Days of orders read in one step. */
    public static final int CHUNK_DAYS = 30;
    static final String COMPLETED_ACTION = "INTEGRATION_HISTORY_COMPLETED";
    private static final CommerceSource SOURCE = CommerceSource.WOOCOMMERCE;
    private static final int PAGE_SIZE = 100;
    private static final Logger log = LoggerFactory.getLogger(WooHistoryImport.class);

    private final WooReader reader;
    private final SyncStateRepository state;
    private final IntegrationConnectionRepository connectionRepository;
    private final OrderRepository orders;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    WooHistoryImport(WooReader reader, SyncStateRepository state,
            IntegrationConnectionRepository connectionRepository, OrderRepository orders, AuditTrail audit,
            TransactionTemplate transactions, Clock clock) {
        this.reader = reader;
        this.state = state;
        this.connectionRepository = connectionRepository;
        this.orders = orders;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Where the history of a connection stands; read by the integration view. */
    public HistoryProgress progress(TrustedStoreScope scope, UUID connectionId) {
        Optional<SyncRun> initial = state.lastSuccessfulImport(scope, connectionId);
        if (initial.isEmpty()) {
            return new HistoryProgress(HistoryProgress.Status.WAITING_FOR_IMPORT, null, null);
        }
        Instant target = target(scope, initial.get());
        Instant importedFrom = importedFrom(scope, connectionId, initial.get());
        return new HistoryProgress(importedFrom.isAfter(target) ? HistoryProgress.Status.IN_PROGRESS
                : HistoryProgress.Status.COMPLETE, importedFrom, target);
    }

    /**
     * Reads the next chunk before the watermark. Only a chunk whose order count matches the source's total moves
     * the watermark and extends the coverage; anything else is read again by the next step.
     */
    public HistoryStep importNext(TrustedStoreScope scope, UUID connectionId, Runnable heartbeat) {
        Optional<SyncRun> initial = state.lastSuccessfulImport(scope, connectionId);
        if (initial.isEmpty()) {
            return new HistoryStep(HistoryStep.Outcome.NOT_READY, null, null, 0);
        }
        Instant target = target(scope, initial.get());
        Instant until = importedFrom(scope, connectionId, initial.get());
        if (!until.isAfter(target)) {
            return new HistoryStep(HistoryStep.Outcome.ALREADY_COMPLETE, until, target, 0);
        }
        Instant from = until.minus(Duration.ofDays(CHUNK_DAYS));
        if (from.isBefore(target)) {
            from = target;
        }
        Instant startedAt = clock.instant();
        try {
            WooReader.Source source = reader.source(scope);
            if (!source.connection().id().equals(connectionId)) {
                throw new WooImportException(ConnectionCheckError.AUTH_FAILED, "Connection was replaced");
            }
            Counts counts = read(scope, source, from, until, heartbeat);
            if (counts.expected() != counts.read()) {
                check(scope, connectionId, SyncCheck.Status.INCOMPLETE, startedAt, counts.read(),
                        "ORDER_COUNT_MISMATCH");
                return new HistoryStep(HistoryStep.Outcome.INCOMPLETE, until, target, counts.read());
            }
            Instant chunkFrom = from;
            boolean completed = !chunkFrom.isAfter(target);
            transactions.executeWithoutResult(status -> {
                orders.extendCoverageBack(scope, SOURCE, chunkFrom, until);
                state.moveBack(scope, connectionId, SyncStream.HISTORY, chunkFrom);
                if (completed) {
                    Map<String, String> details = new LinkedHashMap<>();
                    details.put("connectionId", connectionId.toString());
                    details.put("from", chunkFrom.toString());
                    audit.record(AuditEntry.inScope(scope, COMPLETED_ACTION, "integration_connection",
                            connectionId.toString(), details));
                }
            });
            check(scope, connectionId, SyncCheck.Status.SUCCEEDED, startedAt, counts.read(), null);
            return new HistoryStep(completed ? HistoryStep.Outcome.COMPLETED : HistoryStep.Outcome.IMPORTED,
                    chunkFrom, target, counts.read());
        } catch (WooImportException e) {
            log.warn("History import of connection {} failed: {}", connectionId, e.getMessage());
            check(scope, connectionId, SyncCheck.Status.FAILED, startedAt, 0, e.error().name());
            return new HistoryStep(HistoryStep.Outcome.FAILED, until, target, 0);
        }
    }

    /** Orders placed in [from, until), upserted without outbox events: history triggers no detection. */
    private Counts read(TrustedStoreScope scope, WooReader.Source source, Instant from, Instant until,
            Runnable heartbeat) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("per_page", Integer.toString(PAGE_SIZE));
        // "after" is exclusive (ADR-0008); an order exactly at "until" belongs to the newer chunk and is upserted again.
        query.put("after", WooPayloads.wooDate(from.minusSeconds(1)));
        query.put("before", WooPayloads.wooDate(until));
        query.put("dates_are_gmt", "true");
        query.put("orderby", "date");
        query.put("order", "asc");
        query.put("status", "any");
        query.put("_fields", WooCommerceImport.ORDER_FIELDS);
        long expected = -1;
        int read = 0;
        for (int page = 1; ; page++) {
            query.put("page", Integer.toString(page));
            WooResponse response = reader.response(source, "orders", query);
            if (response == null) {
                throw new WooImportException(ConnectionCheckError.NOT_WOOCOMMERCE, "Orders endpoint missing");
            }
            if (expected < 0) {
                expected = response.total().orElse(-1);
            }
            JsonNode items = WooReader.body(response);
            for (JsonNode order : items) {
                orders.upsert(scope, SOURCE, WooPayloads.order(order));
                read++;
            }
            heartbeat.run();
            if (items.size() < PAGE_SIZE) {
                return new Counts(read, expected);
            }
        }
    }

    private Instant target(TrustedStoreScope scope, SyncRun initial) {
        ZoneId zone = ZoneId.of(connectionRepository.storeTimezone(scope));
        return LocalDate.ofInstant(initial.windowUntil(), zone).minusMonths(HISTORY_MONTHS).atStartOfDay(zone)
                .toInstant();
    }

    private Instant importedFrom(TrustedStoreScope scope, UUID connectionId, SyncRun initial) {
        return state.watermark(scope, connectionId, SyncStream.HISTORY).orElse(initial.windowFrom());
    }

    private void check(TrustedStoreScope scope, UUID connectionId, SyncCheck.Status status, Instant startedAt,
            int orders, String errorCode) {
        state.recordCheck(scope, connectionId, new SyncCheck(SyncCheck.Kind.HISTORY, status, startedAt,
                clock.instant(), orders, 0, errorCode));
    }

    private record Counts(int read, long expected) {
    }

    /** One step of the history import. */
    public record HistoryStep(Outcome outcome, Instant importedFrom, Instant target, int orders) {

        public enum Outcome {
            /** No successful initial import yet. */
            NOT_READY,
            /** A chunk was imported; more remain. */
            IMPORTED,
            /** This chunk reached the target. */
            COMPLETED,
            /** Nothing to do. */
            ALREADY_COMPLETE,
            /** The source count did not match: nothing moved. */
            INCOMPLETE,
            /** The source could not be read: nothing moved. */
            FAILED;

            public boolean finished() {
                return this == COMPLETED || this == ALREADY_COMPLETE || this == NOT_READY;
            }
        }
    }

    /** The history of a connection: how far back orders are imported, and how far they should go. */
    public record HistoryProgress(Status status, Instant importedFrom, Instant target) {

        public enum Status {
            WAITING_FOR_IMPORT,
            IN_PROGRESS,
            COMPLETE
        }
    }
}
