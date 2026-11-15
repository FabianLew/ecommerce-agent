package com.ecommerceagent.identity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Applies the {@link StoreRowGuard} binding to every connection the application uses (ADR-0022 point 3). The
 * application connects as {@code operator_runtime}, which every store policy applies to; this class only sets the
 * scope ({@code app.store_id}, {@code app.organization_id}): on checkout, again before a statement whenever the
 * thread's binding differs from what the connection has (a transaction that started unbound and then calls a
 * store-scoped method, for example), and back to empty when the connection is returned. Empty settings match no
 * store row. A connection whose settings cannot be applied or reset is evicted from the pool.
 */
public final class RowSecurityDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(RowSecurityDataSource.class);

    private static final String APPLY = """
            SELECT set_config('app.store_id', ?, false), set_config('app.organization_id', ?, false)""";

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

    private Connection apply(Connection connection) throws SQLException {
        TrustedStoreScope scope = StoreRowGuard.current().orElse(null);
        ScopedHandler handler = new ScopedHandler(connection);
        try {
            handler.apply(scope);
        } catch (SQLException | RuntimeException e) {
            evict(connection);
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(ConnectionProxy.class.getClassLoader(),
                new Class<?>[] {ConnectionProxy.class}, handler);
    }

    private static String key(TrustedStoreScope scope) {
        return scope == null ? "" : scope.storeId() + "/" + scope.organizationId();
    }

    private static void set(Connection connection, String storeId, String organizationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(APPLY)) {
            statement.setString(1, storeId);
            statement.setString(2, organizationId);
            statement.execute();
        }
    }

    /** Removes the connection from the pool: Hikari evicts it, any other pool gets an aborted connection. */
    private void evict(Connection connection) {
        try {
            DataSource target = getTargetDataSource();
            if (target instanceof com.zaxxer.hikari.HikariDataSource hikari) {
                hikari.evictConnection(connection);
                return;
            }
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not evict a connection after a failed scope change", e);
        }
    }

    private final class ScopedHandler implements InvocationHandler {

        private final Connection target;
        private boolean closed;
        /** The scope key the connection's settings hold; {@code null} when unknown (after a rollback). */
        private String applied;

        ScopedHandler(Connection target) {
            this.target = target;
        }

        void apply(TrustedStoreScope scope) throws SQLException {
            String wanted = key(scope);
            if (wanted.equals(applied)) {
                return;
            }
            applied = null;
            set(target, scope == null ? "" : scope.storeId().toString(),
                    scope == null ? "" : scope.organizationId().toString());
            applied = wanted;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getTargetConnection":
                    return target;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "RowSecurity[" + target + "]";
                case "isClosed":
                    return closed || target.isClosed();
                case "close":
                    if (!closed) {
                        closed = true;
                        reset();
                    }
                    return null;
                default:
                    break;
            }
            if (closed) {
                throw new SQLException("Connection is closed");
            }
            String name = method.getName();
            if (name.equals("prepareStatement") || name.equals("createStatement") || name.equals("prepareCall")) {
                try {
                    apply(StoreRowGuard.current().orElse(null));
                } catch (SQLException | RuntimeException e) {
                    closed = true;
                    evict(target);
                    throw e;
                }
            }
            try {
                Object result = method.invoke(target, args);
                if (name.equals("rollback")) {
                    // A rollback also undoes settings changed inside the transaction; apply them again next time.
                    applied = null;
                }
                return result;
            } catch (InvocationTargetException e) {
                if (name.equals("rollback")) {
                    applied = null;
                }
                throw e.getTargetException();
            }
        }

        private void reset() throws SQLException {
            try {
                if (!target.isClosed()) {
                    boolean autoCommit = target.getAutoCommit();
                    if (!autoCommit) {
                        target.rollback();
                    }
                    set(target, "", "");
                    if (!autoCommit) {
                        target.commit();
                    }
                }
            } catch (SQLException | RuntimeException e) {
                evict(target);
                return;
            }
            target.close();
        }
    }
}
