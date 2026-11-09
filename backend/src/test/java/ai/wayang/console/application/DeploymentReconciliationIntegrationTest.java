package ai.wayang.console.application;

import ai.wayang.console.testsupport.PostgresTestResource;
import ai.wayang.console.adapter.persistence.DeploymentReconciliationEntity;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class DeploymentReconciliationIntegrationTest {
    @Inject RuntimeRegistryService registry;
    @Inject DeploymentLifecycleService deployments;
    @Inject DeploymentReconciliationService reconciliation;
    @Inject RuntimeInventoryService inventory;
    @Inject RuntimeCommandService commands;

    @Test
    @TestTransaction
    void acceptedObservationMovesDesiredPresentIntoSyncAndRejectsReplay() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "reconcile-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "a".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtime.id(), "reconcile-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", digest, digest, "test-operator");

        var desired = reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");
        assertEquals("WAITING_RUNTIME", desired.reconciliationState());
        var observation = new DeploymentReconciliationService.Observation(
                "RUNNING", digest, UUID.randomUUID(), 1, 1, Instant.now(), null);
        var accepted = reconciliation.report(runtime, deployment.id(), observation);
        assertEquals("IN_SYNC", accepted.reconciliationState());
        assertEquals(1, accepted.reportSequence());
        assertNotNull(accepted.receivedAt());
        assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> reconciliation.report(runtime, deployment.id(), observation));
    }

    @Test
    @TestTransaction
    void wrongRuntimeCannotReportAndMismatchedArtifactIsDrift() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        var firstToken = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var first = registry.enroll(firstToken.token(), "reconcile-a-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var secondToken = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var second = registry.enroll(secondToken.token(), "reconcile-b-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var owner = registry.authenticate(first.registrationToken());
        var other = registry.authenticate(second.registrationToken());
        String expected = "sha256:" + "b".repeat(64);
        String actual = "sha256:" + "c".repeat(64);
        var deployment = deployments.create(org, Set.of(env), owner.id(), "reconcile-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", expected, expected, "test-operator");
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> reconciliation.report(other,
                deployment.id(), new DeploymentReconciliationService.Observation(
                        "RUNNING", actual, null, 1, 1, Instant.now(), "wrong runtime")));
        var accepted = reconciliation.report(owner, deployment.id(),
                new DeploymentReconciliationService.Observation("RUNNING", actual, null, 1, 1, Instant.now(), null));
        assertEquals("DRIFTED", accepted.reconciliationState());
    }
    @Test
    @TestTransaction
    void rejectsObservationFromOlderDesiredGeneration() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "generation-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "e".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtime.id(), "generation-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", digest, digest, "test-operator");
        var first = reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");
        var second = reconciliation.setDesired(org, Set.of(env), deployment.id(), "ABSENT", "test-operator");
        assertEquals(1L, first.generation());
        assertEquals(2L, second.generation());

        var stale = new DeploymentReconciliationService.Observation(
                "RUNNING", digest, UUID.randomUUID(), 1, 1, Instant.now(), "late report from generation 1");
        assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> reconciliation.report(runtime, deployment.id(), stale));

        var current = new DeploymentReconciliationService.Observation(
                "ABSENT", null, UUID.randomUUID(), 2, 1, Instant.now(), null);
        var accepted = reconciliation.report(runtime, deployment.id(), current);
        assertEquals("IN_SYNC", accepted.reconciliationState());
        assertEquals(2L, accepted.generation());
    }

    @Test
    @TestTransaction
    void timedOutObservationSchedulesBackoffAndQueuesOneRetry() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "retry-" + UUID.randomUUID(), "1.2.0", null, null, null);
        UUID runtimeId = UUID.fromString(enrolled.registrationId());
        inventory.replaceInventory(runtimeId,
                java.util.List.of(new RuntimeInventoryService.CapabilityInput(
                        "wayang.runtime", "1.2.0", "workflow.execute")), java.util.List.of());
        String digest = "sha256:" + "f".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtimeId, "retry-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");

        Instant now = Instant.now();
        DeploymentReconciliationEntity row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        row.updatedAt = now.minus(Duration.ofMinutes(3));
        row.persist();

        assertEquals(1, reconciliation.scheduleTimedOutRetries(now));
        row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        assertEquals("RETRYING", row.reconciliationState);
        assertEquals(1, row.retryCount);
        assertEquals(now.plusSeconds(30).truncatedTo(ChronoUnit.MICROS), row.nextAttemptAt);

        assertTrue(commands.dispatchDueRetry(deployment.id(), now.plusSeconds(30)));
        row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        assertEquals("WAITING_RUNTIME", row.reconciliationState);
        assertNull(row.nextAttemptAt);
        assertEquals(1, row.retryCount);
        assertNotNull(row.lastAttemptAt);
        ai.wayang.console.adapter.persistence.ManagementCommandEntity retryCommand = ai.wayang.console.adapter.persistence.ManagementCommandEntity
                .find("idempotencyKey", "deployment:" + deployment.id() + ":generation:1:retry:1:apply:v1").firstResult();
        assertNotNull(retryCommand);
        assertTrue(retryCommand.typedPayload.contains("\"generation\":1"));
        assertTrue(retryCommand.typedPayload.contains("\"retryAttempt\":1"));
        assertFalse(commands.dispatchDueRetry(deployment.id(), now.plusSeconds(30)));
    }

    @Test
    @TestTransaction
    void exhaustedReconciliationRetriesRequireManualIntervention() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "retry-exhausted-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "9".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtime.id(), "retry-exhausted-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");
        Instant now = Instant.now();
        DeploymentReconciliationEntity row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        row.retryCount = 5;
        row.updatedAt = now.minus(Duration.ofMinutes(3));
        row.persist();

        assertEquals(1, reconciliation.scheduleTimedOutRetries(now));
        row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        assertEquals("MANUAL_INTERVENTION", row.reconciliationState);
        assertNull(row.nextAttemptAt);
        assertTrue(row.diagnostic.contains("5 automatic retries"));
    }

}
