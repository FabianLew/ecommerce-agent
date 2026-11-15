package com.ecommerceagent;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Direct database access for test fixtures and assertions, as the container's superuser: it sets up and inspects
 * rows of any store without a binding. Never a {@code DataSource} bean, so the application keeps connecting as the
 * runtime role under row-level security (ADR-0022).
 */
public final class FixtureDb {

    private final DriverManagerDataSource dataSource;
    private final JdbcClient jdbc;

    FixtureDb(String url, String username, String password) {
        this.dataSource = new DriverManagerDataSource(url, username, password);
        this.jdbc = JdbcClient.create(dataSource);
    }

    public JdbcClient.StatementSpec sql(String sql) {
        return jdbc.sql(sql);
    }

    public JdbcClient client() {
        return jdbc;
    }

    /** Runs {@code work} in one superuser transaction and commits it (an exception rolls it back). */
    public void inTransaction(Consumer<JdbcClient> work) {
        transaction(work, false);
    }

    /** Runs {@code work} in one superuser transaction and always rolls it back. */
    public void rolledBack(Consumer<JdbcClient> work) {
        transaction(work, true);
    }

    private void transaction(Consumer<JdbcClient> work, boolean rollback) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.accept(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
                if (rollback) {
                    connection.rollback();
                } else {
                    connection.commit();
                }
            } catch (RuntimeException | Error e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
