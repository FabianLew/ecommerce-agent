package com.ecommerceagent.integration;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Logs at start how many stored credentials still use a key other than the current one (ADR-0022 point 4), so an
 * operator knows when a previous key may be removed. Counts only; never reads a value.
 */
@Component
class SecretKeyUsage implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretKeyUsage.class);

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    SecretKeyUsage(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> others = jdbc.sql("""
                        SELECT key_id || ': ' || count(*) FROM (
                            SELECT key_id FROM integration_credential
                            UNION ALL
                            SELECT key_id FROM meta_credential
                        ) used
                        WHERE key_id <> :current
                        GROUP BY key_id ORDER BY key_id
                        """)
                .param("current", cipher.keyId())
                .query(String.class).list();
        if (!others.isEmpty()) {
            log.info("Credentials sealed with a previous key (resealed when next used): {}", others);
        }
    }
}
