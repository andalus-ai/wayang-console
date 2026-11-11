package ai.wayang.console.application;

import ai.wayang.console.testsupport.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import ai.wayang.console.adapter.persistence.RuntimeInstanceEntity;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class RuntimeCommandIntegrationTest {
    @Inject RuntimeRegistryService registry;
    @Inject DeploymentLifecycleService deployments;
    @Inject RuntimeCommandService commands;
    @Inject RuntimeInventoryService inventory;
    @Inject RuntimeCompatibilityEvaluator compatibility;
    @Inject DeploymentReconciliationService reconciliation;

    @Test
    @TestTransaction
    void deploymentCommandIsIdempotentlyQueuedLeasedAndAcknowledged() {
        UUID organization = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "command-runtime-" + UUID.randomUUID(), "1.0.0", null, null, null);
        UUID runtimeId = UUID.fromString(enrolled.registrationId());
        String digest = "sha256:" + "a".repeat(64);
        inventory.replaceInventory(runtimeId,
                java.util.List.of(new RuntimeInventoryService.CapabilityInput("wayang.runtime", "1.2.0", "workflow.execute")),
                java.util.List.of());
        var deployment = deployments.create(organization, Set.of(environment), runtimeId, "release-1",
                "sample.workflow", "1.0.0", digest, digest, "1.0.0",
                java.util.List.of(new DeploymentLifecycleService.CapabilityRequirement("wayang.runtime", "workflow.execute", "1.1.0")),
                "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "VALIDATING", null, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "READY", null, "operator-test");

        var absent = reconciliation.setDesired(organization, Set.of(environment), deployment.id(), "ABSENT", "operator-test");
        assertEquals(1L, absent.generation());
        assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> commands.dispatch(organization, Set.of(environment), deployment.id(), "operator-test"));
        var present = reconciliation.setDesired(organization, Set.of(environment), deployment.id(), "PRESENT", "operator-test");
        assertEquals(2L, present.generation());

        var command = commands.dispatch(organization, Set.of(environment), deployment.id(), "operator-test");
        assertTrue(command.idempotencyKey().contains(":generation:2:"));
        assertTrue(command.typedPayload().contains("\"generation\":2"));
        assertThrows(jakarta.ws.rs.WebApplicationException.class, () -> deployments.transition(
                organization, Set.of(environment), deployment.id(), "DEPLOYED", null, "operator-test"));
        var repeated = commands.dispatch(organization, Set.of(environment), deployment.id(), "operator-test");
        assertEquals(command.commandId(), repeated.commandId());
        assertTrue(command.typedPayload().contains("sample.workflow"));
        assertTrue(command.typedPayload().contains("\"minimumRuntimeVersion\":\"1.0.0\""));
        assertTrue(command.typedPayload().contains("wayang.runtime"));

        var runtime = registry.authenticate(enrolled.registrationToken());
        var polled = commands.poll(runtime, 10);
        assertEquals(1, polled.commands().size());
        assertEquals(command.commandId(), polled.commands().getFirst().commandId());
        assertEquals(1, polled.commands().getFirst().attemptCount());

        var started = commands.acknowledge(runtime, command.commandId(), "STARTED", "accepted", null);
        assertEquals("RUNNING", started.status());
        assertThrows(jakarta.ws.rs.WebApplicationException.class, () ->
                commands.acknowledge(runtime, command.commandId(), "SUCCEEDED", "missing evidence", null));
        assertThrows(jakarta.ws.rs.WebApplicationException.class, () ->
                commands.acknowledge(runtime, command.commandId(), "SUCCEEDED", "wrong evidence",
                        "sha256:" + "f".repeat(64)));
        var completed = commands.acknowledge(runtime, command.commandId(), "SUCCEEDED", "applied", digest);
        assertTrue(completed.accepted());
        assertEquals("SUCCEEDED", completed.status());
        assertEquals("DEPLOYED", deployments.list(organization, Set.of(environment), 0, 10)
                .items().getFirst().status());
        assertTrue(commands.acknowledge(runtime, command.commandId(), "SUCCEEDED", "duplicate", digest).accepted());
    }

    @Test
    @TestTransaction
    void pendingCommandIsSupersededBeforeRuntimeDelivery() {
        UUID organization = UUID.randomUUID(), environment = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "pending-supersede-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        inventory.replaceInventory(runtime.id(),
                java.util.List.of(new RuntimeInventoryService.CapabilityInput("wayang.runtime", "1.2.0", "workflow.execute")),
                java.util.List.of());
        String digest = "sha256:" + "e".repeat(64);
        var deployment = deployments.create(organization, Set.of(environment), runtime.id(),
                "pending-supersede-" + UUID.randomUUID(), "orders.flow", "1.0.0", digest, digest, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "VALIDATING", null, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "READY", null, "operator-test");
        reconciliation.setDesired(organization, Set.of(environment), deployment.id(), "PRESENT", "operator-test");
        var command = commands.dispatch(organization, Set.of(environment), deployment.id(), "operator-test");

        var next = reconciliation.setDesired(organization, Set.of(environment), deployment.id(), "ABSENT", "operator-test");
        assertEquals(2L, next.generation());
        var history = commands.listDeploymentCommands(organization, Set.of(environment), deployment.id(), 0, 10);
        assertEquals(1L, history.total());
        assertEquals("SUPERSEDED", history.items().getFirst().status());
        assertEquals(1L, history.items().getFirst().generation());
        assertEquals(2L, history.items().getFirst().supersededByGeneration());
        assertNotNull(history.items().getFirst().supersededAt());
        assertTrue(commands.poll(runtime, 10).commands().isEmpty());
        assertEquals(command.commandId(), history.items().getFirst().commandId());
    }

    @Test
    @TestTransaction
    void leasedCommandRequiresRuntimeSupersessionAcknowledgement() {
        UUID organization = UUID.randomUUID(), environment = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "leased-supersede-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        inventory.replaceInventory(runtime.id(),
                java.util.List.of(new RuntimeInventoryService.CapabilityInput("wayang.runtime", "1.2.0", "workflow.execute")),
                java.util.List.of());
        String digest = "sha256:" + "f".repeat(64);
        var deployment = deployments.create(organization, Set.of(environment), runtime.id(),
                "leased-supersede-" + UUID.randomUUID(), "orders.flow", "1.0.0", digest, digest, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "VALIDATING", null, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "READY", null, "operator-test");
        reconciliation.setDesired(organization, Set.of(environment), deployment.id(), "PRESENT", "operator-test");
        var command = commands.dispatch(organization, Set.of(environment), deployment.id(), "operator-test");
        assertEquals(1, commands.poll(runtime, 10).commands().size());

        reconciliation.setDesired(organization, Set.of(environment), deployment.id(), "ABSENT", "operator-test");
        var pendingAck = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> commands.acknowledge(runtime, command.commandId(), "STARTED", "late start", null));
        assertEquals(409, pendingAck.getResponse().getStatus());
        var confirmed = commands.acknowledge(runtime, command.commandId(), "SUPERSEDED", "runtime discarded queued apply", null);
        assertTrue(confirmed.accepted());
        assertEquals("SUPERSEDED", confirmed.status());
        var history = commands.listDeploymentCommands(organization, Set.of(environment), deployment.id(), 0, 10);
        assertEquals("SUPERSEDED", history.items().getFirst().status());
        assertEquals(2L, history.items().getFirst().supersededByGeneration());
    }

    @Test
    @TestTransaction
    void runningCommandCompletionAfterSupersessionCannotMarkNewGenerationDeployed() {
        UUID organization = UUID.randomUUID(), environment = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "running-supersede-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        inventory.replaceInventory(runtime.id(),
                java.util.List.of(new RuntimeInventoryService.CapabilityInput("wayang.runtime", "1.2.0", "workflow.execute")),
                java.util.List.of());
        String digest = "sha256:" + "9".repeat(64);
        var deployment = deployments.create(organization, Set.of(environment), runtime.id(),
                "running-supersede-" + UUID.randomUUID(), "orders.flow", "1.0.0", digest, digest, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "VALIDATING", null, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "READY", null, "operator-test");
        reconciliation.setDesired(organization, Set.of(environment), deployment.id(), "PRESENT", "operator-test");
        var command = commands.dispatch(organization, Set.of(environment), deployment.id(), "operator-test");
        commands.poll(runtime, 10);
        assertEquals("RUNNING", commands.acknowledge(runtime, command.commandId(), "STARTED", "started", null).status());

        reconciliation.setDesired(organization, Set.of(environment), deployment.id(), "ABSENT", "operator-test");
        var completed = commands.acknowledge(runtime, command.commandId(), "SUCCEEDED", "finished during supersession", digest);
        assertEquals("STALE_SUCCEEDED", completed.status());
        assertEquals("DEPLOYING", deployments.list(organization, Set.of(environment), 0, 10)
                .items().getFirst().status());
        assertEquals("WAITING_RUNTIME",
                reconciliation.get(organization, Set.of(environment), deployment.id()).reconciliationState());
    }

    @Test
    @TestTransaction
    void dispatchRejectsMissingCapabilityAndReportsDiagnostic() {
        UUID organization = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "compat-runtime-" + UUID.randomUUID(), "1.0.0", null, null, null);
        UUID runtimeId = UUID.fromString(enrolled.registrationId());
        inventory.replaceInventory(runtimeId,
                java.util.List.of(new RuntimeInventoryService.CapabilityInput("wayang.runtime", "1.0.0", "workflow.execute")),
                java.util.List.of());
        String digest = "sha256:" + "c".repeat(64);
        var deployment = deployments.create(organization, Set.of(environment), runtimeId, "release-compat",
                "sample.workflow", "1.0.0", digest, digest, "1.1.0",
                java.util.List.of(new DeploymentLifecycleService.CapabilityRequirement(
                        "wayang.connector.kafka", "messaging.kafka", "2.0.0")), "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "VALIDATING", null, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "READY", null, "operator-test");

        var evaluation = compatibility.evaluate(deployment.id(), organization, Set.of(environment));
        assertFalse(evaluation.compatible());
        assertTrue(evaluation.diagnostics().stream().anyMatch(d -> "CAPABILITY_MISSING".equals(d.code())));
        assertTrue(evaluation.diagnostics().stream().anyMatch(d -> "RUNTIME_VERSION_UNSUPPORTED".equals(d.code())));
        assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> commands.dispatch(organization, Set.of(environment), deployment.id(), "operator-test"));
        assertEquals("READY", deployments.list(organization, Set.of(environment), 0, 10)
                .items().getFirst().status());
    }

    @Test
    @TestTransaction
    void dispatchRejectsStaleInventoryEvenWhenRuntimeLeaseIsLive() {
        UUID organization = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "stale-runtime-" + UUID.randomUUID(), "1.0.0", null, null, null);
        UUID runtimeId = UUID.fromString(enrolled.registrationId());
        inventory.replaceInventory(runtimeId, java.util.List.of(), java.util.List.of());
        RuntimeInstanceEntity runtime = RuntimeInstanceEntity.findById(runtimeId);
        runtime.inventoryReportedAt = Instant.now().minus(Duration.ofMinutes(6));
        runtime.leaseExpiresAt = Instant.now().plus(Duration.ofMinutes(1));
        String digest = "sha256:" + "d".repeat(64);
        var deployment = deployments.create(organization, Set.of(environment), runtimeId, "release-stale",
                "sample.workflow", "1.0.0", digest, digest, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "VALIDATING", null, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "READY", null, "operator-test");

        var evaluation = compatibility.evaluate(deployment.id(), organization, Set.of(environment));
        assertFalse(evaluation.compatible());
        assertTrue(evaluation.diagnostics().stream().anyMatch(d -> "INVENTORY_STALE".equals(d.code())));
        assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> commands.dispatch(organization, Set.of(environment), deployment.id(), "operator-test"));
    }

    @Test
    @TestTransaction
    void runtimeCannotAcknowledgeAnotherRuntimesCommand() {
        UUID organization = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        var enrollmentA = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolledA = registry.enroll(enrollmentA.token(), "command-owner-" + UUID.randomUUID(), "1.0.0", null, null, null);
        var enrollmentB = registry.issueEnrollmentToken(organization, environment, Duration.ofHours(1));
        var enrolledB = registry.enroll(enrollmentB.token(), "command-other-" + UUID.randomUUID(), "1.0.0", null, null, null);
        UUID runtimeA = UUID.fromString(enrolledA.registrationId());
        String digest = "sha256:" + "b".repeat(64);
        inventory.replaceInventory(runtimeA, java.util.List.of(), java.util.List.of());
        var deployment = deployments.create(organization, Set.of(environment), runtimeA, "release-2",
                "sample.workflow", "1.0.0", digest, digest, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "VALIDATING", null, "operator-test");
        deployments.transition(organization, Set.of(environment), deployment.id(), "READY", null, "operator-test");
        var command = commands.dispatch(organization, Set.of(environment), deployment.id(), "operator-test");
        var otherRuntime = registry.authenticate(enrolledB.registrationToken());
        assertThrows(jakarta.ws.rs.NotFoundException.class, () ->
                commands.acknowledge(otherRuntime, command.commandId(), "SUCCEEDED", "forged", digest));
    }
}
