package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.RuntimeCapabilityEntity;
import ai.wayang.console.adapter.persistence.RuntimeInstanceEntity;
import ai.wayang.console.testsupport.PostgresTestResource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class RuntimeInventoryIntegrationTest {
    @Inject RuntimeRegistryService registry;
    @Inject RuntimeInventoryService inventory;

    @Test
    @TestTransaction
    void authenticatedSnapshotIsValidatedReplacedAndRefreshesRuntimeLease() {
        UUID organization = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        var token = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolled = registry.enroll(token.token(), "inventory-" + UUID.randomUUID(), "1.2.3", null, null, null);
        UUID runtimeId = UUID.fromString(enrolled.registrationId());

        var first = inventory.replaceInventory(runtimeId,
                List.of(new RuntimeInventoryService.CapabilityInput("wayang.runtime", "1.2.3", "workflow.execute")),
                List.of());
        assertEquals(1, first.capabilityCount());
        assertNotNull(first.revision());
        assertNotNull(first.reportedAt());

        RuntimeInstanceEntity runtime = RuntimeInstanceEntity.findById(runtimeId);
        assertEquals(first.revision(), runtime.inventoryRevision);
        assertEquals(first.reportedAt(), runtime.inventoryReportedAt);
        assertTrue(runtime.leaseExpiresAt.isAfter(Instant.now()));
        assertEquals("ONLINE", runtime.status);
        assertEquals(1, RuntimeCapabilityEntity.count("runtimeId", runtimeId));

        assertThrows(IllegalArgumentException.class, () -> inventory.replaceInventory(runtimeId,
                List.of(
                        new RuntimeInventoryService.CapabilityInput("wayang.runtime", "1.2.3", "workflow.execute"),
                        new RuntimeInventoryService.CapabilityInput("WAYANG.RUNTIME", "1.2.3", "WORKFLOW.EXECUTE")),
                List.of()));
        assertEquals(1, RuntimeCapabilityEntity.count("runtimeId", runtimeId),
                "invalid replacement must preserve the previous snapshot");

        var replacement = inventory.replaceInventory(runtimeId,
                List.of(new RuntimeInventoryService.CapabilityInput("wayang.runtime", "1.2.3", "workflow.execute"),
                        new RuntimeInventoryService.CapabilityInput("wayang.runtime", "1.2.3", "artifact.verify")),
                List.of());
        assertNotEquals(first.revision(), replacement.revision());
        assertEquals(2, RuntimeCapabilityEntity.count("runtimeId", runtimeId));
    }

    @Test
    @TestTransaction
    void inventoryRejectsOversizedAndMalformedWorkflowMetadata() {
        UUID organization = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        var token = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolled = registry.enroll(token.token(), "inventory-invalid-" + UUID.randomUUID(), "1.2.3", null, null, null);
        UUID runtimeId = UUID.fromString(enrolled.registrationId());

        assertThrows(IllegalArgumentException.class, () -> inventory.replaceInventory(runtimeId,
                List.of(new RuntimeInventoryService.CapabilityInput("x".repeat(181), "1", "cap")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> inventory.replaceInventory(runtimeId, List.of(),
                List.of(new RuntimeInventoryService.WorkflowInput("wf", "1", "not-a-digest", "deployment"))));
        assertEquals(0, RuntimeCapabilityEntity.count("runtimeId", runtimeId));
    }
}
