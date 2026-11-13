package ai.wayang.console.testsupport;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.Map;

public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {
    private PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("wayang_console_test")
                .withUsername("wayang_console")
                .withPassword("wayang_console");
        postgres.start();
        return Map.of(
                "quarkus.datasource.jdbc.url", postgres.getJdbcUrl(),
                "quarkus.datasource.username", postgres.getUsername(),
                "quarkus.datasource.password", postgres.getPassword(),
                "quarkus.liquibase.migrate-at-start", "true",
                // Non-empty sentinel satisfies required config injection; tests register managed publisher keys.
                "wayang.console.artifacts.signing-key-id", "test-only-unmanaged-key",
                // Valid DER-encoded Ed25519 test key; managed publisher keys are registered per test.
                "wayang.console.artifacts.ed25519-public-key", "MCowBQYDK2VwAyEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
        );
    }

    @Override
    public void stop() {
        if (postgres != null) postgres.stop();
    }
}
