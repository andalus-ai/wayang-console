package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.EnrollmentTokenEntity;
import ai.wayang.console.adapter.persistence.RuntimeCapabilityEntity;
import ai.wayang.console.testsupport.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class RuntimeRegistryIntegrationTest {
    @Inject RuntimeRegistryService registry;
    @Inject OperatorFleetService fleet;
    @Inject RuntimeInventoryService inventory;

    @Test
    @TestTransaction
    void enrollmentCredentialCanOnlyBeRedeemedOnceAndRuntimeCanBeRevoked() {
        UUID organizationId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        var token = registry.issueEnrollmentToken(organizationId, environmentId, Duration.ofHours(1));

        var enrolled = registry.enroll(token.token(), "integration-runtime-a", "1.0.0", "deployment-a", "host-a", "linux");
        assertEquals(organizationId, registry.authenticate(enrolled.registrationToken()).organizationId());
        assertEquals(environmentId, registry.authenticate(enrolled.registrationToken()).environmentId());

        assertThrows(IllegalArgumentException.class, () ->
                registry.enroll(token.token(), "integration-runtime-b", "1.0.0", null, null, null));

        UUID runtimeId = UUID.fromString(enrolled.registrationId());
        fleet.revokeRuntime(organizationId, Set.of(environmentId), runtimeId, "integration-test");
        assertThrows(SecurityException.class, () -> registry.authenticate(enrolled.registrationToken()));
    }

    @Test
    @TestTransaction
    void revokedEnrollmentCredentialCannotBeRedeemed() {
        UUID organizationId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        var token = registry.issueEnrollmentToken(organizationId, environmentId, Duration.ofHours(1));
        EnrollmentTokenEntity entity = EnrollmentTokenEntity.findById(token.tokenId());
        entity.revokedAt = Instant.now();

        assertThrows(IllegalArgumentException.class, () ->
                registry.enroll(token.token(), "integration-runtime-revoked", "1.0.0", null, null, null));
    }

    @Test
    @TestTransaction
    void fleetQueriesAreRestrictedToOrganizationAndEnvironmentScope() {
        UUID organizationA = UUID.randomUUID();
        UUID environmentA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        UUID environmentB = UUID.randomUUID();

        var tokenA = registry.issueEnrollmentToken(organizationA, environmentA, Duration.ofHours(1));
        var tokenB = registry.issueEnrollmentToken(organizationB, environmentB, Duration.ofHours(1));
        var runtimeA = registry.enroll(tokenA.token(), "scope-runtime-a", "1.0.0", null, null, null);
        var runtimeB = registry.enroll(tokenB.token(), "scope-runtime-b", "1.0.0", null, null, null);

        var page = fleet.list(organizationA, Set.of(environmentA), null, 0, 25);
        assertEquals(1, page.total());
        assertEquals("scope-runtime-a", page.items().getFirst().instanceId());

        assertThrows(NotFoundException.class, () -> fleet.get(organizationA, Set.of(environmentA),
                UUID.fromString(runtimeB.registrationId())));
    }

    @Test
    @TestTransaction
    void invalidInventoryDoesNotEraseLastKnownInventory() {
        UUID organizationId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        var token = registry.issueEnrollmentToken(organizationId, environmentId, Duration.ofHours(1));
        var enrolled = registry.enroll(token.token(), "inventory-runtime", "1.0.0", null, null, null);
        UUID runtimeId = UUID.fromString(enrolled.registrationId());

        inventory.replaceInventory(runtimeId,
                List.of(new RuntimeInventoryService.CapabilityInput("wayang.eip.router", "1.0.0", "routing")),
                List.of());
        assertEquals(1, RuntimeCapabilityEntity.count("runtimeId", runtimeId));

        assertThrows(IllegalArgumentException.class, () -> inventory.replaceInventory(runtimeId,
                Arrays.asList((RuntimeInventoryService.CapabilityInput) null), List.of()));
        assertEquals(1, RuntimeCapabilityEntity.count("runtimeId", runtimeId));
    }

    @Test
    @TestTransaction
    void runtimeFleetPaginationReturnsStablePageCounts() {
        UUID organizationId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            var token = registry.issueEnrollmentToken(organizationId, environmentId, Duration.ofHours(1));
            registry.enroll(token.token(), "page-runtime-" + i, "1.0.0", null, null, null);
        }

        var first = fleet.list(organizationId, Set.of(environmentId), null, 0, 2);
        var second = fleet.list(organizationId, Set.of(environmentId), null, 1, 2);
        assertEquals(3, first.total());
        assertEquals(2, first.items().size());
        assertEquals(1, second.items().size());
    }
}
