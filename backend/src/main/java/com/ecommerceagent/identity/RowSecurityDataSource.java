package com.ecommerceagent.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Applies the {@link StoreRowGuard} binding to every checked-out connection (ADR-0022 point 3): the store role and
 * the scope when bound, the login role and empty settings when not. One statement per checkout; connections are
 * handed out in autocommit mode, so the session settings are in force before any transaction starts.
 */
public final class RowSecurityDataSource extends DelegatingDataSource {

    /** The role every store policy applies to; created by migration V23. */
    public static final String STORE_ROLE = "operator_app";

    private static final String APPLY = """
            SELECT set_config('role', ?, false), set_config('app.store_id', ?, false),
                   set_config('app.organization_id', ?, false)""";

    public RowSecurityDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return apply(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return apply(super.getConnection(username, password));
    }

    private static Connection apply(Connection connection) throws SQLException {
        TrustedStoreScope scope = StoreRowGuard.current().orElse(null);
        try (PreparedStatement statement = connection.prepareStatement(APPLY)) {
            statement.setString(1, scope == null ? "none" : STORE_ROLE);
            statement.setString(2, scope == null ? "" : scope.storeId().toString());
            statement.setString(3, scope == null ? "" : scope.organizationId().toString());
            statement.execute();
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }
}
