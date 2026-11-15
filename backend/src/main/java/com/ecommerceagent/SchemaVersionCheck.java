package com.ecommerceagent;

import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * When the application does not migrate (cloud profile, ADR-0023 point 3), refuses to start on a schema that is
 * older than the migrations packaged in this image or that has a failed migration. A newer schema is accepted: the
 * migrations are backward compatible with the previous release. Reads {@code flyway_schema_history} as the runtime
 * role, which may only select from it.
 */
@Component
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
class SchemaVersionCheck implements SmartInitializingSingleton {

    private final DataSource dataSource;

    SchemaVersionCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        MigrationInfo[] all;
        try {
            all = Flyway.configure().dataSource(dataSource).locations(MigrateCommand.LOCATION)
                    .ignoreMigrationPatterns("*:future").load().info().all();
        } catch (FlywayException e) {
            // Before the runtime role's grants exist (V25), it cannot read the history either.
            throw new IllegalStateException("Schema history is not readable by the runtime role; run the migration "
                    + "step first", e);
        }
        String failed = Arrays.stream(all).filter(info -> info.getState().isFailed())
                .map(info -> String.valueOf(info.getVersion())).reduce((a, b) -> a + ", " + b).orElse(null);
        if (failed != null) {
            throw new IllegalStateException("Schema has failed migrations: " + failed);
        }
        String pending = Arrays.stream(all).filter(info -> info.getState() == MigrationState.PENDING)
                .map(info -> String.valueOf(info.getVersion())).reduce((a, b) -> a + ", " + b).orElse(null);
        if (pending != null) {
            throw new IllegalStateException("Schema is older than this release; run the migration step first. "
                    + "Pending: " + pending);
        }
    }
}
