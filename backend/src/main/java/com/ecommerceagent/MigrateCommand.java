package com.ecommerceagent;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * The migration step of a deployment (ADR-0023 point 3): applies the migrations packaged in this image as the
 * owner of the schema ({@code operator_migrator}, ADR-0022), validates them and exits. It starts no Spring context,
 * web server, worker or scheduler. Run from the image with
 * {@code java -Dloader.main=com.ecommerceagent.MigrateCommand -cp app.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}, with {@code DB_URL}, {@code DB_MIGRATION_USERNAME}
 * and {@code DB_MIGRATION_PASSWORD} in the environment. A non-zero exit status stops the rollout.
 */
public final class MigrateCommand {

    static final String LOCATION = "classpath:db/migration";

    private MigrateCommand() {
    }

    public static void main(String[] args) {
        String url = required("DB_URL");
        String user = required("DB_MIGRATION_USERNAME");
        String password = required("DB_MIGRATION_PASSWORD");
        try {
            MigrateResult result = migrate(url, user, password);
            System.out.println("Migrated " + result.migrationsExecuted + " migration(s); schema version "
                    + result.targetSchemaVersion);
        } catch (RuntimeException e) {
            System.err.println("Migration failed: " + e.getMessage());
            System.exit(1);
        }
    }

    static MigrateResult migrate(String url, String user, String password) {
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(LOCATION)
                .load();
        MigrateResult result = flyway.migrate();
        flyway.validate();
        return result;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println(name + " is required");
            System.exit(2);
        }
        return value;
    }
}
