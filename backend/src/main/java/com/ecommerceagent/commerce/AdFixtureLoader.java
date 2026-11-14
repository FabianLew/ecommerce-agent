package com.ecommerceagent.commerce;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.ecommerceagent.identity.StoreAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local stand-in for the Meta adapter (B-20): loads the ad fixture file every minute (or
 * {@code operator.commerce.ad-fixture-interval}), so ads appear once the catalog import has created their variants
 * and their state stays as fresh as a polled ad. Only in the local worker, and only when a file is configured.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local & worker")
@EnableScheduling
@ConditionalOnProperty(name = "operator.commerce.ad-fixture-file")
class AdFixtureLoader {

    static final String SERVICE = "ad-fixture";
    private static final Logger log = LoggerFactory.getLogger(AdFixtureLoader.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AdFixtures fixtures;
    private final StoreAccess storeAccess;
    private final Path file;

    AdFixtureLoader(AdFixtures fixtures, StoreAccess storeAccess,
            @Value("${operator.commerce.ad-fixture-file}") Path file) {
        this.fixtures = fixtures;
        this.storeAccess = storeAccess;
        this.file = file;
    }

    @Scheduled(initialDelayString = "10s", fixedDelayString = "${operator.commerce.ad-fixture-interval:60s}")
    void load() {
        try {
            List<AdFixture> stores = JSON.readValue(Files.readString(file), new TypeReference<>() {
            });
            for (AdFixture fixture : stores) {
                storeAccess.systemScope(SERVICE, fixture.organizationId(), fixture.storeId())
                        .ifPresent(scope -> fixtures.load(scope, fixture));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Loading ad fixture {} failed", file, e);
        }
    }
}
