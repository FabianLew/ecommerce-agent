package com.ecommerceagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * ADR-0023 point 3: the migration step applies the migrations as the migrator, and a runtime process refuses to start
 * on an older schema. Each test uses its own database in one container.
 */
class MigrationStepTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @BeforeAll
    static void start() {
        POSTGRES.withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("..", "infra", "postgres", "init", "02-operator-roles.sql")),
                "/docker-entrypoint-initdb.d/02-operator-roles.sql");
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void theMigrationStepAppliesEverythingAsTheMigratorAndTheRuntimeStartsOnIt() {
        String url = database("complete");
        var result = MigrateCommand.migrate(url, "operator_migrator", "operator_migrator");

        assertThat(result.migrationsExecuted).isPositive();
        JdbcClient admin = JdbcClient.create(new DriverManagerDataSource(url, POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        assertThat(admin.sql("SELECT DISTINCT installed_by FROM flyway_schema_history").query(String.class).list())
                .containsExactly("operator_migrator");
        assertThat(admin.sql("SELECT tableowner FROM pg_tables WHERE tablename = 'decision_proposal'")
                .query(String.class).single()).isEqualTo("operator_migrator");

        new SchemaVersionCheck(runtime(url)).afterSingletonsInstantiated();
    }

    @Test
    void aRuntimeProcessRefusesAnOlderSchema() {
        String url = database("older");
        Flyway.configure().dataSource(url, "operator_migrator", "operator_migrator")
                .locations(MigrateCommand.LOCATION).target("24").load().migrate();

        assertThatThrownBy(() -> new SchemaVersionCheck(runtime(url)).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run the migration step first");
    }

    @Test
    void theRuntimeRoleCannotMigrate() {
        String url = database("runtime");
        assertThatThrownBy(() -> MigrateCommand.migrate(url, "operator_runtime", "operator_runtime"))
                .hasMessageContaining("permission denied");
    }

    private static DriverManagerDataSource runtime(String url) {
        return new DriverManagerDataSource(url, "operator_runtime", "operator_runtime");
    }

    /** A new database owned by the migrator, as the role bootstrap sets up the main one. */
    private static String database(String name) {
        JdbcClient admin = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        admin.sql("CREATE DATABASE " + name + " OWNER operator_migrator").update();
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
        JdbcClient db = JdbcClient.create(new DriverManagerDataSource(url, POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        db.sql("ALTER SCHEMA public OWNER TO operator_migrator").update();
        db.sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC").update();
        return url;
    }
}
