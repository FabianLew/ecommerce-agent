package com.ecommerceagent;

import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL for integration tests; same major version and the same role bootstrap as infra/docker-compose
 * (ADR-0022): Flyway migrates as {@code operator_migrator}, the application connects as {@code operator_runtime},
 * and test fixtures write through {@link FixtureDb} as the container's superuser.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String POSTGRES_IMAGE = "postgres:17.6";

    private static final Path ROLES = Path.of("..", "infra", "postgres", "init", "02-operator-roles.sql");

    @Bean
    PostgreSQLContainer postgres() {
        PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES_IMAGE);
        container.withCopyFileToContainer(MountableFile.forHostPath(ROLES),
                "/docker-entrypoint-initdb.d/02-operator-roles.sql");
        return container;
    }

    @Bean
    DynamicPropertyRegistrar postgresProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> "operator_runtime");
            registry.add("spring.datasource.password", () -> "operator_runtime");
            registry.add("spring.flyway.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", () -> "operator_migrator");
            registry.add("spring.flyway.password", () -> "operator_migrator");
        };
    }

    @Bean
    FixtureDb fixtureDb(PostgreSQLContainer postgres) {
        return new FixtureDb(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
