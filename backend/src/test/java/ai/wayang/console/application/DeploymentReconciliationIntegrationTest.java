package ai.wayang.console.application;

import ai.wayang.console.testsupport.PostgresTestResource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class DeploymentReconciliationIntegrationTest {
    @Inject RuntimeRegistryService registry;
    @Inject DeploymentLifecycleService deployments;
    @Inject DeploymentReconciliationService reconciliation;

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
                "RUNNING", digest, UUID.randomUUID(), 1, Instant.now(), null);
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
                        "RUNNING", actual, null, 1, Instant.now(), "wrong runtime")));
        var accepted = reconciliation.report(owner, deployment.id(),
                new DeploymentReconciliationService.Observation("RUNNING", actual, null, 1, Instant.now(), null));
        assertEquals("DRIFTED", accepted.reconciliationState());
    }
}
