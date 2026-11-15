package com.ecommerceagent.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The platform operators (ADR-0027 section 15): users listed in {@code platform_operator}, which only reviewed
 * changes with the administration role write. A revoked entry counts as absent.
 */
@Service
public class PlatformOperators {

    /** The system actor under which a platform operation reads and writes one store's rows. */
    public static final String OPERATIONS_SERVICE = "platform-operations";

    private final JdbcClient jdbc;
    private final StoreAccess storeAccess;

    PlatformOperators(JdbcClient jdbc, StoreAccess storeAccess) {
        this.jdbc = jdbc;
        this.storeAccess = storeAccess;
    }

    public Optional<PlatformOperator> find(UUID userId) {
        boolean listed = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM platform_operator WHERE user_id = ? AND revoked_at IS NULL)
                        """)
                .param(userId).query(Boolean.class).single();
        return listed ? Optional.of(new PlatformOperator(userId)) : Optional.empty();
    }

    /** @throws PlatformOperatorDeniedException if the user is not a platform operator now */
    public PlatformOperator require(UUID userId) {
        return find(userId).orElseThrow(PlatformOperatorDeniedException::new);
    }

    /**
     * Re-checks the operator and returns a read scope of the store for the operation, with the system actor
     * {@value #OPERATIONS_SERVICE}; who decided is recorded separately with {@link PlatformOperator#recordedAs()}.
     *
     * @throws PlatformOperatorDeniedException if the operator was revoked
     */
    public Optional<TrustedStoreScope> operationsScope(PlatformOperator operator, UUID organizationId,
            UUID storeId) {
        require(operator.userId());
        return storeAccess.systemScope(OPERATIONS_SERVICE, organizationId, storeId);
    }

    /** As {@link #operationsScope(PlatformOperator, UUID, UUID)} for a store known only by its id. */
    public Optional<TrustedStoreScope> operationsScope(PlatformOperator operator, UUID storeId) {
        require(operator.userId());
        return jdbc.sql("SELECT organization_id FROM store WHERE id = ?").param(storeId).query(UUID.class)
                .optional()
                .flatMap(organizationId -> storeAccess.systemScope(OPERATIONS_SERVICE, organizationId, storeId));
    }
}
