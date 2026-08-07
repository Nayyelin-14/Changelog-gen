package com.hubsabai.changelog.storage;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;

/**
 * Safety guard for destructive test statements (TRUNCATE, broad DELETE). Inspects the actual
 * live connection URL and fails loudly if it looks like the real dev/prod database (neon.tech)
 * instead of an isolated Dev Services container. Belt-and-suspenders after a test once TRUNCATE'd
 * the shared database via a misconfigured test profile.
 */
final class TestDatabaseGuard {

    private TestDatabaseGuard() {}

    static void assertNotProductionDatabase(EntityManager entityManager) {
        entityManager.unwrap(Session.class).doWork(connection -> {
            String url = connection.getMetaData().getURL();
            if (url != null && url.contains("neon.tech")) {
                throw new IllegalStateException(
                        "Refusing to run a destructive test statement — this connection is pointed at "
                        + url + ", which looks like the real dev/prod database, not an isolated "
                        + "Dev Services container. Fix the datasource config before running this test.");
            }
        });
    }
}
