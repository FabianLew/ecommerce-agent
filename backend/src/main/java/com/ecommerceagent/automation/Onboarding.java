package com.ecommerceagent.automation;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.ecommerceagent.businessprofile.BusinessProfiles;
import com.ecommerceagent.commerce.AdMappingScope;
import com.ecommerceagent.commerce.AdMappings;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.ConnectionStatus;
import com.ecommerceagent.integration.IntegrationConnection;
import com.ecommerceagent.integration.IntegrationConnections;
import com.ecommerceagent.integration.IntegrationProvider;
import com.ecommerceagent.integration.MetaConnection;
import com.ecommerceagent.integration.MetaConnectionStatus;
import com.ecommerceagent.integration.MetaConnections;
import com.ecommerceagent.integration.SyncRun;
import com.ecommerceagent.integration.SyncRunStatus;
import com.ecommerceagent.integration.WooCommerceImport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Onboarding of an existing store (document 04 §12, ADR-0018). Steps and readiness are computed from the real
 * connection, import, mapping and profile state; the stored progress is only the current step and acknowledgements.
 */
@Service
public class Onboarding {

    public enum Step {
        CONNECT_STORE,
        CONNECT_TOOLS,
        BUSINESS_DATA,
        START_MONITORING
    }

    public enum Domain {
        COMMERCE,
        ADS,
        AD_MAPPINGS,
        PROFILE
    }

    public enum Readiness {
        READY,
        IN_PROGRESS,
        NOT_STARTED,
        SKIPPED,
        ERROR
    }

    /** Acknowledgements a user may record; none of them completes a step by itself except skipping Meta. */
    public enum Acknowledgement {
        SKIP_META,
        MAPPINGS_LATER
    }

    public record StepState(Step step, boolean complete, List<String> blockers) {
    }

    public record DomainState(Domain domain, Readiness readiness, String detail) {
    }

    public record OnboardingState(Step currentStep, Step nextStep, List<StepState> steps, List<DomainState> domains,
            Set<Acknowledgement> acknowledgements, int version) {
    }

    private final JdbcClient jdbc;
    private final IntegrationConnections connections;
    private final WooCommerceImport imports;
    private final MetaConnections meta;
    private final AdMappings mappings;
    private final BusinessProfiles profiles;
    private final Clock clock;

    Onboarding(JdbcClient jdbc, IntegrationConnections connections, WooCommerceImport imports, MetaConnections meta,
            AdMappings mappings, BusinessProfiles profiles, Clock clock) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.imports = imports;
        this.meta = meta;
        this.mappings = mappings;
        this.profiles = profiles;
        this.clock = clock;
    }

    public OnboardingState state(TrustedStoreScope scope) {
        Progress progress = progress(scope);
        Optional<IntegrationConnection> woo = connections.list(scope).stream()
                .filter(c -> c.provider() == IntegrationProvider.WOOCOMMERCE).findFirst();
        Optional<SyncRun> lastImport = woo.flatMap(c -> imports.latestRun(scope, c.id()));
        Optional<MetaConnection> metaConnection = meta.find(scope);
        boolean profileApproved = profiles.approved(scope).isPresent();
        long verifiedSingle = mappings.list(scope).stream()
                .filter(o -> o.state() == AdMappings.MappingState.VERIFIED
                        && o.mapping().scope() == AdMappingScope.SINGLE_VARIANT)
                .count();

        List<DomainState> domains = new ArrayList<>();
        DomainState commerce = commerce(woo, lastImport);
        domains.add(commerce);
        boolean skipMeta = progress.acknowledgements().contains(Acknowledgement.SKIP_META);
        DomainState ads = ads(metaConnection, skipMeta);
        domains.add(ads);
        domains.add(new DomainState(Domain.AD_MAPPINGS, verifiedSingle > 0 ? Readiness.READY
                : metaConnection.isPresent() ? Readiness.IN_PROGRESS : Readiness.NOT_STARTED,
                Long.toString(verifiedSingle)));
        domains.add(new DomainState(Domain.PROFILE, profileApproved ? Readiness.READY
                : profiles.state(scope).draft() != null ? Readiness.IN_PROGRESS : Readiness.NOT_STARTED, null));

        List<StepState> steps = new ArrayList<>();
        steps.add(new StepState(Step.CONNECT_STORE, commerce.readiness() == Readiness.READY,
                commerce.readiness() == Readiness.READY ? List.of() : List.of(commerce.detail())));
        boolean toolsDone = ads.readiness() == Readiness.READY || ads.readiness() == Readiness.SKIPPED;
        steps.add(new StepState(Step.CONNECT_TOOLS, toolsDone, toolsDone ? List.of() : List.of(ads.detail())));
        List<String> dataBlockers = new ArrayList<>();
        if (!profileApproved) {
            dataBlockers.add("PROFILE_NOT_APPROVED");
        }
        if (verifiedSingle == 0 && !skipMeta
                && !progress.acknowledgements().contains(Acknowledgement.MAPPINGS_LATER)) {
            dataBlockers.add("NO_VERIFIED_MAPPING");
        }
        steps.add(new StepState(Step.BUSINESS_DATA, dataBlockers.isEmpty(), List.copyOf(dataBlockers)));
        // Starting monitoring is a later step (B-35/F4): it is never complete here.
        steps.add(new StepState(Step.START_MONITORING, false, List.of("NOT_AVAILABLE_YET")));
        Step next = steps.stream().filter(s -> !s.complete()).map(StepState::step).findFirst()
                .orElse(Step.START_MONITORING);
        return new OnboardingState(progress.currentStep(), next, List.copyOf(steps), List.copyOf(domains),
                progress.acknowledgements(), progress.version());
    }

    /** Stores the user's position and acknowledgements; readiness stays computed (ADR-0018). */
    @Transactional
    public OnboardingState update(TrustedStoreScope scope, Step currentStep, Set<Acknowledgement> acknowledgements,
            int expectedVersion) {
        scope.require(StorePermission.STORE_MANAGE);
        if (currentStep == null) {
            throw new OnboardingConflictException("currentStep is required", false);
        }
        Set<Acknowledgement> acks = acknowledgements == null ? Set.of() : Set.copyOf(acknowledgements);
        String[] values = acks.stream().map(Enum::name).sorted().toArray(String[]::new);
        Timestamp now = Timestamp.from(clock.instant());
        int written;
        if (expectedVersion == 0) {
            written = jdbc.sql("""
                            INSERT INTO onboarding_progress (store_id, organization_id, current_step, acknowledgements,
                                                             version, updated_by, updated_at)
                            VALUES (:storeId, :organizationId, :step, CAST(:acks AS text[]), 1, :user, :now)
                            ON CONFLICT (store_id) DO NOTHING
                            """)
                    .param("storeId", scope.storeId())
                    .param("organizationId", scope.organizationId())
                    .param("step", currentStep.name())
                    .param("acks", "{" + String.join(",", values) + "}")
                    .param("user", scope.userId().orElse(null), java.sql.Types.OTHER)
                    .param("now", now)
                    .update();
        } else {
            written = jdbc.sql("""
                            UPDATE onboarding_progress
                            SET current_step = :step, acknowledgements = CAST(:acks AS text[]), version = version + 1,
                                updated_by = :user, updated_at = :now
                            WHERE store_id = :storeId AND organization_id = :organizationId AND version = :version
                            """)
                    .param("step", currentStep.name())
                    .param("acks", "{" + String.join(",", values) + "}")
                    .param("user", scope.userId().orElse(null), java.sql.Types.OTHER)
                    .param("now", now)
                    .param("storeId", scope.storeId())
                    .param("organizationId", scope.organizationId())
                    .param("version", expectedVersion)
                    .update();
        }
        if (written != 1) {
            throw new OnboardingConflictException("The onboarding progress changed since it was loaded", true);
        }
        return state(scope);
    }

    private DomainState commerce(Optional<IntegrationConnection> woo, Optional<SyncRun> lastImport) {
        if (woo.isEmpty()) {
            return new DomainState(Domain.COMMERCE, Readiness.NOT_STARTED, "WOOCOMMERCE_NOT_CONNECTED");
        }
        if (woo.get().status() == ConnectionStatus.ERROR) {
            return new DomainState(Domain.COMMERCE, Readiness.ERROR, "WOOCOMMERCE_ERROR");
        }
        if (woo.get().status() != ConnectionStatus.CONNECTED) {
            return new DomainState(Domain.COMMERCE, Readiness.IN_PROGRESS, "WOOCOMMERCE_CHECK_REQUIRED");
        }
        if (lastImport.isEmpty()) {
            return new DomainState(Domain.COMMERCE, Readiness.IN_PROGRESS, "IMPORT_NOT_STARTED");
        }
        return switch (lastImport.get().status()) {
            case SUCCEEDED -> new DomainState(Domain.COMMERCE, Readiness.READY, null);
            case RUNNING -> new DomainState(Domain.COMMERCE, Readiness.IN_PROGRESS, "IMPORT_RUNNING");
            default -> new DomainState(Domain.COMMERCE, Readiness.ERROR, "IMPORT_FAILED");
        };
    }

    private static DomainState ads(Optional<MetaConnection> connection, boolean skipped) {
        if (connection.isEmpty()) {
            return new DomainState(Domain.ADS, skipped ? Readiness.SKIPPED : Readiness.NOT_STARTED,
                    "META_NOT_CONNECTED");
        }
        return switch (connection.get().status()) {
            case CONNECTED -> new DomainState(Domain.ADS, Readiness.READY,
                    connection.get().canWrite() ? null : "META_READ_ONLY");
            case ERROR -> new DomainState(Domain.ADS, Readiness.ERROR, "META_ERROR");
            default -> new DomainState(Domain.ADS, skipped ? Readiness.SKIPPED : Readiness.IN_PROGRESS,
                    "META_CHECK_REQUIRED");
        };
    }

    private record Progress(Step currentStep, Set<Acknowledgement> acknowledgements, int version) {
    }

    private Progress progress(TrustedStoreScope scope) {
        return jdbc.sql("""
                        SELECT current_step, acknowledgements, version FROM onboarding_progress
                        WHERE store_id = :storeId AND organization_id = :organizationId
                        """)
                .param("storeId", scope.storeId())
                .param("organizationId", scope.organizationId())
                .query((rs, n) -> new Progress(Step.valueOf(rs.getString("current_step")),
                        Arrays.stream((String[]) rs.getArray("acknowledgements").getArray())
                                .map(Acknowledgement::valueOf)
                                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Acknowledgement.class))),
                        rs.getInt("version")))
                .optional()
                .orElse(new Progress(Step.CONNECT_STORE, Set.of(), 0));
    }
}
