package com.ecommerceagent.identity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maps verified OIDC identities to application users, registering them on first use. */
@Service
public class UserDirectory {

    private final JdbcClient jdbc;
    private final AuditTrail audit;

    UserDirectory(JdbcClient jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public RegisteredUser register(UserIdentity identity) {
        Optional<RegisteredUser> existing = find(identity);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        INSERT INTO app_user (id, issuer, subject, email, display_name)
                        VALUES (:id, :issuer, :subject, :email, :displayName)
                        ON CONFLICT (issuer, subject) DO NOTHING
                        """)
                .param("id", id)
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .param("email", identity.email())
                .param("displayName", identity.displayName())
                .update();
        if (inserted == 0) {
            // A concurrent first request registered the same identity.
            return find(identity).orElseThrow();
        }
        audit.record(new AuditEntry("USER_REGISTERED", null, null, id, "user", id.toString(),
                Map.of("issuer", identity.issuer())));
        return new RegisteredUser(id, identity.email(), identity.displayName());
    }

    private Optional<RegisteredUser> find(UserIdentity identity) {
        return jdbc.sql("SELECT id, email, display_name FROM app_user WHERE issuer = :issuer AND subject = :subject")
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .query((rs, n) -> new RegisteredUser(rs.getObject("id", UUID.class),
                        rs.getString("email"), rs.getString("display_name")))
                .optional();
    }
}
