package com.ecommerceagent.aigovernance;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * Parameter set {@code ai-admission} (ADR-0027 section 6.2): admission priorities with reserved slots, and bounded
 * waiting for a slot.
 *
 * <p>Each automation has a priority ({@code priorities}, by registered automation type; any other automation has
 * {@code defaultPriority}). A priority may fill a share of the platform's concurrent runs
 * ({@code operator.ai-governance.max-concurrent-runs}): {@code CRITICAL} all of it, {@code STANDARD}
 * {@code standardCapacityPercent}, {@code BACKGROUND} {@code backgroundCapacityPercent}, at least one run each. The
 * slots above a share stay free for the higher priorities, so a burst of lower-priority work never takes the last
 * slots from critical work. The per-store limit applies to every priority alike.
 *
 * <p>A run refused for a full store or platform waits in its workflow (a durable Temporal timer, no thread is held)
 * and asks again: first after {@code initialWait}, then after as long as it has already waited, at most
 * {@code maxWaitStep} per step, minus up to {@code waitJitterPercent} percent so that waiting runs do not ask
 * together. After {@code maxWait} in total it stops waiting and the caller degrades as before (the baseline).
 * {@code maxWait} zero turns waiting off.
 *
 * <p>A changed default needs a new version ({@code ParameterSetVersionTest}); the cloud profile refuses an override
 * that keeps the default version.
 */
@ConfigurationProperties(AiAdmissionSettings.PREFIX)
public record AiAdmissionSettings(
        @DefaultValue(DEFAULT_VERSION) String version,
        @DefaultValue("STANDARD") Priority defaultPriority,
        Map<String, Priority> priorities,
        @DefaultValue("75") int standardCapacityPercent,
        @DefaultValue("50") int backgroundCapacityPercent,
        @DefaultValue("2m") Duration maxWait,
        @DefaultValue("5s") Duration initialWait,
        @DefaultValue("30s") Duration maxWaitStep,
        @DefaultValue("20") int waitJitterPercent) {

    public static final String DEFAULT_VERSION = "ai-admission/1";

    public static final String PREFIX = "operator.ai-admission";

    /** The priorities when none are configured: the Inventory Guardian protects stock that is running out. */
    static final Map<String, Priority> DEFAULT_PRIORITIES = Map.of("inventory-agent", Priority.CRITICAL);

    public enum Priority {
        CRITICAL,
        STANDARD,
        BACKGROUND
    }

    public AiAdmissionSettings {
        if (version == null || !version.matches("ai-admission/[0-9]+")) {
            throw new IllegalArgumentException(PREFIX + ".version must look like ai-admission/N, was " + version);
        }
        if (defaultPriority == null) {
            throw new IllegalArgumentException(PREFIX + ".default-priority is required");
        }
        priorities = Map.copyOf(new TreeMap<>(priorities == null ? DEFAULT_PRIORITIES : priorities));
        priorities.forEach((automation, priority) -> {
            if (!automation.matches("[a-z0-9-]{1,64}") || priority == null) {
                throw new IllegalArgumentException(PREFIX + ".priorities needs registered automation types, was "
                        + automation);
            }
        });
        if (standardCapacityPercent < 1 || standardCapacityPercent > 100 || backgroundCapacityPercent < 1
                || backgroundCapacityPercent > standardCapacityPercent) {
            throw new IllegalArgumentException(PREFIX
                    + " capacity percents must be 1 <= background <= standard <= 100");
        }
        if (maxWait == null || initialWait == null || maxWaitStep == null || maxWait.isNegative()
                || maxWait.compareTo(Duration.ofMinutes(15)) > 0 || initialWait.compareTo(Duration.ofSeconds(1)) < 0
                || maxWaitStep.compareTo(initialWait) < 0 || maxWaitStep.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(PREFIX
                    + " waits must be 0 <= max-wait <= 15m and 1s <= initial-wait <= max-wait-step <= 5m");
        }
        if (waitJitterPercent < 0 || waitJitterPercent > 50) {
            throw new IllegalArgumentException(PREFIX + ".wait-jitter-percent must be 0 to 50");
        }
    }

    public static AiAdmissionSettings defaults() {
        return new Binder(List.of()).bindOrCreate(PREFIX, AiAdmissionSettings.class);
    }

    public Priority priorityOf(String automationType) {
        return priorities.getOrDefault(automationType, defaultPriority);
    }

    /** How many of the platform's concurrent runs a run of this priority may fill; at least one. */
    public int capacity(Priority priority, int maxConcurrentRuns) {
        int percent = switch (priority) {
            case CRITICAL -> 100;
            case STANDARD -> standardCapacityPercent;
            case BACKGROUND -> backgroundCapacityPercent;
        };
        return Math.max(1, maxConcurrentRuns * percent / 100);
    }

    /**
     * How long to wait before asking for a slot again, after {@code waited} in total; empty when the next wait would
     * pass {@code maxWait}. Whole seconds, at least one.
     */
    public Optional<Duration> nextWait(Duration waited, RandomGenerator random) {
        Duration step = waited.compareTo(initialWait) < 0 ? initialWait
                : waited.compareTo(maxWaitStep) > 0 ? maxWaitStep : waited;
        long seconds = Math.max(1, step.toSeconds()
                - step.toSeconds() * random.nextInt(waitJitterPercent + 1) / 100);
        Duration next = Duration.ofSeconds(seconds);
        if (waited.plus(next).compareTo(maxWait) > 0) {
            return Optional.empty();
        }
        return Optional.of(next);
    }

    /** The parameters in a fixed order. */
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("version", version);
        parameters.put("defaultPriority", defaultPriority.name());
        new TreeMap<>(priorities).forEach((automation, priority) ->
                parameters.put("priorities." + automation, priority.name()));
        parameters.put("standardCapacityPercent", Integer.toString(standardCapacityPercent));
        parameters.put("backgroundCapacityPercent", Integer.toString(backgroundCapacityPercent));
        parameters.put("maxWait", maxWait.toString());
        parameters.put("initialWait", initialWait.toString());
        parameters.put("maxWaitStep", maxWaitStep.toString());
        parameters.put("waitJitterPercent", Integer.toString(waitJitterPercent));
        return parameters;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiAdmissionSettings.class)
    static class Registration {
    }
}
