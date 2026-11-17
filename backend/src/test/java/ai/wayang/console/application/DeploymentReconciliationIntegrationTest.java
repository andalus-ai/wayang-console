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
    @Inject AuditIntegrityService audit;
    @Inject ReconciliationSlaAlertDispatcher alertDispatcher;
    @Inject TestReconciliationSlaAlertSink testAlertSink;
    @Inject ReconciliationSlaWebhookSink webhookSink;

    @Test
    @TestTransaction
    void slaAlertPolicyIsOptInByDefault() {
        var policy = reconciliation.reconciliationSlaAlertPolicy();
        assertFalse(policy.warningEnabled());
        assertFalse(policy.criticalEnabled());
        assertFalse(policy.recoveryEnabled());
        assertEquals("AUDIT_REPLAY_WITH_PROVIDER_OWNED_DURABLE_DELIVERY", policy.deliveryMode());
    }

    @Test
    @TestTransaction
    void disabledSlaAlertPolicyDoesNotCreateDeliveryIntent() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "alert-disabled-" + UUID.randomUUID(),
                "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "9".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtime.id(),
                "alert-disabled-" + UUID.randomUUID(), "alerts.flow", "1.0.0",
                digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");
        var row = DeploymentReconciliationEntity.<DeploymentReconciliationEntity>find(
                "deploymentId", deployment.id()).firstResult();
        Instant now = Instant.now();
        row.updatedAt = now.minus(Duration.ofMinutes(10));
        row.persist();

        assertEquals(1, reconciliation.evaluateSlaTransitions(now));
        assertEquals(0L, ai.wayang.console.adapter.persistence.AuditRecordEntity.count(
                "organizationId = ?1 and environmentId = ?2 and action like ?3",
                org, env, "RECONCILIATION_ALERT_%"));
        assertEquals(1L, ai.wayang.console.adapter.persistence.AuditRecordEntity.count(
                "organizationId = ?1 and environmentId = ?2 and action like ?3",
                org, env, "RECONCILIATION_SLA_%_TO_WARNING"));
    }

    @Test
    @TestTransaction
    void alertIntentReplaysInOrderAndProviderCursorMakesReplayIdempotent() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID(), deploymentId = UUID.randomUUID();
        var alert = audit.append(org, env, "console:test", "RECONCILIATION_ALERT_WARNING_TO_CRITICAL",
                "DEPLOYMENT", deploymentId.toString(), Instant.now(), "SUCCESS");

        assertEquals(1, alertDispatcher.replayPendingTransitions());
        assertEquals(0, alertDispatcher.replayPendingTransitions());
        assertEquals(alert.sequenceNo.longValue(), testAlertSink.lastAcceptedSequence(org, env));
        var status = testAlertSink.status(org, Set.of(env));
        assertEquals(1L, status.accepted());
        assertEquals(1L, status.delivered());
        assertEquals(0L, status.pending());
        assertEquals("HEALTHY", status.health());
    }


    @Test
    @TestTransaction
    void durableWebhookSinkAcceptsIdempotentlyAndTracksDelivery() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID(), deploymentId = UUID.randomUUID();
        String prefix = "wayang.console.reconciliation.sla.webhook.destinations." + org + "." + env;
        String previousAllowed = System.getProperty("wayang.console.reconciliation.sla.webhook.allowed-hosts");
        String previousEndpoint = System.getProperty(prefix + ".endpoint");
        String previousSecret = System.getProperty(prefix + ".secret-reference");
        try {
            System.setProperty("wayang.console.reconciliation.sla.webhook.allowed-hosts", "alerts.example.com");
            System.setProperty(prefix + ".endpoint", "https://alerts.example.com/hooks/wayang");
            System.setProperty(prefix + ".secret-reference", "env:WAYANG_TEST_WEBHOOK_SECRET");
            var transition = new DeploymentReconciliationService.SlaAlertTransition(
                    org, env, deploymentId, "WARNING", "CRITICAL",
                    Instant.parse("2026-11-16T00:00:00Z"), "console:test", 42L, "test-record-hash");

            assertEquals(0L, webhookSink.lastAcceptedSequence(org, env));
            webhookSink.accept(transition, 0L);
            assertEquals(42L, webhookSink.lastAcceptedSequence(org, env));
            webhookSink.accept(transition, 0L); // same audit hash is an idempotent acceptance
            assertEquals(1L, ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity.count(
                    "organizationId = ?1 and environmentId = ?2", org, env));
            assertThrows(IllegalStateException.class, () -> webhookSink.accept(
                    new DeploymentReconciliationService.SlaAlertTransition(
                            org, env, UUID.randomUUID(), "CRITICAL", "HEALTHY",
                            Instant.now(), "console:test", 43L, "next-hash"), 0L));

            var claims = webhookSink.claimDue(Instant.now().plusSeconds(1), 10);
            assertEquals(1, claims.size());
            webhookSink.complete(claims.get(0), new ReconciliationSlaWebhookTransport.Response(204));
            var status = webhookSink.status(org, Set.of(env));
            assertEquals(1L, status.accepted());
            assertEquals(0L, status.pending());
            assertEquals(1L, status.delivered());
            assertEquals(0L, status.failed());
            assertEquals("HEALTHY", status.health());
        } finally {
            restoreProperty("wayang.console.reconciliation.sla.webhook.allowed-hosts", previousAllowed);
            restoreProperty(prefix + ".endpoint", previousEndpoint);
            restoreProperty(prefix + ".secret-reference", previousSecret);
        }
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

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
                .find("idempotencyKey", "deployment:" + deployment.id() + ":generation:1:retry:1:scheduled:" + now.plusSeconds(30).toEpochMilli() + ":apply:v1").firstResult();
        assertNotNull(retryCommand);
        assertTrue(retryCommand.typedPayload.contains("\"generation\":1"));
        assertTrue(retryCommand.typedPayload.contains("\"retryAttempt\":1"));
        assertFalse(commands.dispatchDueRetry(deployment.id(), now.plusSeconds(30)));
    }

    @Test
    @TestTransaction
    void operatorCanRetryFailedDeploymentWithAuditedReason() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "operator-retry-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        inventory.replaceInventory(runtime.id(),
                java.util.List.of(new RuntimeInventoryService.CapabilityInput(
                        "wayang.runtime", "1.2.0", "workflow.execute")), java.util.List.of());
        String digest = "sha256:" + "d".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtime.id(), "operator-retry-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");
        var failed = reconciliation.report(runtime, deployment.id(),
                new DeploymentReconciliationService.Observation("FAILED", null, UUID.randomUUID(),
                        1, 1, Instant.now(), "runtime apply failed"));
        assertEquals("MANUAL_INTERVENTION", failed.reconciliationState());

        var retry = reconciliation.requestOperatorRetry(org, Set.of(env), deployment.id(),
                "operator:alice", "runtime capacity restored");
        assertEquals("RETRYING", retry.reconciliationState());
        assertEquals(0, retry.retryCount());
        assertNotNull(retry.nextAttemptAt());
        assertTrue(retry.diagnostic().contains("runtime capacity restored"));
        assertTrue(commands.dispatchDueRetry(deployment.id(), retry.nextAttemptAt()));
        ai.wayang.console.adapter.persistence.ManagementCommandEntity queued =
                ai.wayang.console.adapter.persistence.ManagementCommandEntity
                        .find("deploymentId", deployment.id()).firstResult();
        assertNotNull(queued);
        assertTrue(queued.typedPayload.contains("\"retryAttempt\":0"));

        var timeline = reconciliation.auditTimeline(org, Set.of(env), deployment.id(), 0, 20);
        assertTrue(timeline.items().stream().anyMatch(event ->
                "DEPLOYMENT_OPERATOR_RETRY_REQUESTED".equals(event.action())));
        var cooldown = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> reconciliation.requestOperatorRetry(org, Set.of(env), deployment.id(),
                        "operator:alice", "duplicate click"));
        assertEquals(429, cooldown.getResponse().getStatus());
    }

    @Test
    @TestTransaction
    void reconciliationQueueIsScopedFilteredAndPaginated() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID(), otherEnv = UUID.randomUUID();
        var firstEnrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var firstRegistration = registry.enroll(firstEnrollment.token(), "queue-a-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var firstRuntime = registry.authenticate(firstRegistration.registrationToken());
        String digest = "sha256:" + "a".repeat(64);
        var first = deployments.create(org, Set.of(env), firstRuntime.id(), "queue-a-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", digest, digest, "test-operator");
        var second = deployments.create(org, Set.of(env), firstRuntime.id(), "queue-b-" + UUID.randomUUID(),
                "payments.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), first.id(), "PRESENT", "test-operator");
        reconciliation.setDesired(org, Set.of(env), second.id(), "PRESENT", "test-operator");

        var outsideEnrollment = registry.issueEnrollmentToken(org, otherEnv, Duration.ofHours(1));
        var outsideRegistration = registry.enroll(outsideEnrollment.token(), "queue-outside-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var outsideRuntime = registry.authenticate(outsideRegistration.registrationToken());
        var outside = deployments.create(org, Set.of(otherEnv), outsideRuntime.id(), "queue-outside-" + UUID.randomUUID(),
                "outside.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(otherEnv), outside.id(), "PRESENT", "test-operator");

        var firstPage = reconciliation.listQueue(org, Set.of(env), "WAITING_RUNTIME", 0, 1);
        var secondPage = reconciliation.listQueue(org, Set.of(env), "WAITING_RUNTIME", 1, 1);
        assertEquals(2L, firstPage.total());
        assertEquals(1, firstPage.items().size());
        assertEquals(1, secondPage.items().size());
        assertNotEquals(firstPage.items().get(0).deploymentId(), secondPage.items().get(0).deploymentId());
        assertTrue(firstPage.items().stream().allMatch(item -> env.equals(item.environmentId())));
        assertTrue(secondPage.items().stream().allMatch(item -> env.equals(item.environmentId())));
        assertEquals(0L, reconciliation.listQueue(org, Set.of(env), "MANUAL_INTERVENTION", 0, 10).total());
    }

    @Test
    @TestTransaction
    void fleetSummaryAggregatesOnlyAuthenticatedOrganizationAndEnvironmentScope() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID(), outsideEnv = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "fleet-summary-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "c".repeat(64);
        var waiting = deployments.create(org, Set.of(env), runtime.id(), "fleet-waiting-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", digest, digest, "test-operator");
        var manual = deployments.create(org, Set.of(env), runtime.id(), "fleet-manual-" + UUID.randomUUID(),
                "payments.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), waiting.id(), "PRESENT", "test-operator");
        reconciliation.setDesired(org, Set.of(env), manual.id(), "PRESENT", "test-operator");
        reconciliation.report(runtime, manual.id(), new DeploymentReconciliationService.Observation(
                "FAILED", null, UUID.randomUUID(), 1, 1, Instant.now(), "test failure"));

        var outsideEnrollment = registry.issueEnrollmentToken(org, outsideEnv, Duration.ofHours(1));
        var outsideRegistration = registry.enroll(outsideEnrollment.token(), "fleet-outside-" + UUID.randomUUID(),
                "1.2.0", null, null, null);
        var outsideRuntime = registry.authenticate(outsideRegistration.registrationToken());
        var outside = deployments.create(org, Set.of(outsideEnv), outsideRuntime.id(), "fleet-outside-" + UUID.randomUUID(),
                "outside.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(outsideEnv), outside.id(), "PRESENT", "test-operator");

        DeploymentReconciliationEntity waitingRow = DeploymentReconciliationEntity.find("deploymentId", waiting.id()).firstResult();
        waitingRow.reconciliationState = "RETRYING";
        waitingRow.nextAttemptAt = Instant.now().minusSeconds(10);
        waitingRow.updatedAt = Instant.now().minus(Duration.ofMinutes(5));
        waitingRow.persist();

        Instant now = Instant.now();
        var summary = reconciliation.fleetSummary(org, Set.of(env), now);
        assertEquals(2L, summary.total());
        assertEquals(0L, summary.inSync());
        assertEquals(1L, summary.retrying());
        assertEquals(1L, summary.manualIntervention());
        assertEquals(0L, summary.waitingRuntime());
        assertEquals(1L, summary.dueRetries());
        assertEquals(0L, summary.unresolvedSupersessions());
        assertNotNull(summary.oldestUnresolved());
        assertEquals(waiting.id(), summary.oldestUnresolved().deploymentId());
        assertTrue(summary.oldestUnresolved().ageSeconds() >= 300);
        assertEquals(now, summary.generatedAt());
        assertEquals(0L, reconciliation.fleetSummary(UUID.randomUUID(), Set.of(env), now).total());
        assertEquals(0L, reconciliation.fleetSummary(org, Set.of(), now).total());
    }

    @Test
    @TestTransaction
    void reconciliationHealthClassifiesAgeBandsWithinAuthenticatedScope() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID(), outsideEnv = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "sla-health-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "8".repeat(64);
        var fresh = deployments.create(org, Set.of(env), runtime.id(), "sla-fresh-" + UUID.randomUUID(),
                "fresh.flow", "1.0.0", digest, digest, "test-operator");
        var warning = deployments.create(org, Set.of(env), runtime.id(), "sla-warning-" + UUID.randomUUID(),
                "warning.flow", "1.0.0", digest, digest, "test-operator");
        var critical = deployments.create(org, Set.of(env), runtime.id(), "sla-critical-" + UUID.randomUUID(),
                "critical.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), fresh.id(), "PRESENT", "test-operator");
        reconciliation.setDesired(org, Set.of(env), warning.id(), "PRESENT", "test-operator");
        reconciliation.setDesired(org, Set.of(env), critical.id(), "PRESENT", "test-operator");

        Instant now = Instant.now();
        DeploymentReconciliationEntity freshRow = DeploymentReconciliationEntity.find("deploymentId", fresh.id()).firstResult();
        freshRow.updatedAt = now.minusSeconds(120);
        freshRow.persist();
        DeploymentReconciliationEntity warningRow = DeploymentReconciliationEntity.find("deploymentId", warning.id()).firstResult();
        warningRow.updatedAt = now.minusSeconds(600);
        warningRow.persist();
        DeploymentReconciliationEntity criticalRow = DeploymentReconciliationEntity.find("deploymentId", critical.id()).firstResult();
        criticalRow.updatedAt = now.minusSeconds(1200);
        criticalRow.persist();

        var outsideEnrollment = registry.issueEnrollmentToken(org, outsideEnv, Duration.ofHours(1));
        var outsideRegistration = registry.enroll(outsideEnrollment.token(), "sla-outside-" + UUID.randomUUID(),
                "1.2.0", null, null, null);
        var outsideRuntime = registry.authenticate(outsideRegistration.registrationToken());
        var outside = deployments.create(org, Set.of(outsideEnv), outsideRuntime.id(), "sla-outside-" + UUID.randomUUID(),
                "outside.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(outsideEnv), outside.id(), "PRESENT", "test-operator");

        var health = reconciliation.reconciliationHealth(org, Set.of(env), now);
        assertEquals("CRITICAL", health.status());
        assertEquals(3L, health.unresolved());
        assertEquals(1L, health.fresh());
        assertEquals(1L, health.warning());
        assertEquals(1L, health.critical());
        assertEquals(300L, health.warningThresholdSeconds());
        assertEquals(900L, health.criticalThresholdSeconds());
        assertEquals(critical.id(), health.oldestUnresolved().deploymentId());
        assertEquals(0L, reconciliation.reconciliationHealth(UUID.randomUUID(), Set.of(env), now).unresolved());
        assertEquals("HEALTHY", reconciliation.reconciliationHealth(org, Set.of(), now).status());
    }

    @Test
    @TestTransaction
    void operatorRetryHourlyLimitIsDurableAndReturnsTooManyRequests() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "operator-limit-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "b".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtime.id(), "operator-limit-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");
        reconciliation.report(runtime, deployment.id(),
                new DeploymentReconciliationService.Observation("FAILED", null, UUID.randomUUID(),
                        1, 1, Instant.now(), "runtime apply failed"));
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            audit.append(org, env, "operator:history", "DEPLOYMENT_OPERATOR_RETRY_REQUESTED",
                    "DEPLOYMENT", deployment.id().toString(), now.minus(Duration.ofMinutes(10 - i)), "SUCCESS");
        }
        var limited = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> reconciliation.requestOperatorRetry(org, Set.of(env), deployment.id(),
                        "operator:alice", "retry after investigation"));
        assertEquals(429, limited.getResponse().getStatus());
    }

    @Test
    @TestTransaction
    void expiredHealthyObservationReturnsToWaitingAndIsAuditedOnce() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "freshness-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "7".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtime.id(), "freshness-" + UUID.randomUUID(),
                "orders.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");
        reconciliation.report(runtime, deployment.id(),
                new DeploymentReconciliationService.Observation("RUNNING", digest, UUID.randomUUID(),
                        1, 1, Instant.now().minus(Duration.ofMinutes(3)), null));

        DeploymentReconciliationEntity row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        row.receivedAt = Instant.now().minus(Duration.ofMinutes(3));
        row.persist();
        var staleView = reconciliation.get(org, Set.of(env), deployment.id());
        assertFalse(staleView.observationFresh());
        assertEquals("IN_SYNC", staleView.reconciliationState());

        Instant now = Instant.now();
        assertEquals(1, reconciliation.reconcileExpiredObservations(now));
        row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        assertEquals("WAITING_RUNTIME", row.reconciliationState);
        assertTrue(row.diagnostic.contains("IN_SYNC"));
        assertEquals(0, reconciliation.reconcileExpiredObservations(now.plusSeconds(30)));

        var timeline = reconciliation.auditTimeline(org, Set.of(env), deployment.id(), 0, 20);
        assertEquals(1, timeline.items().stream()
                .filter(event -> "DEPLOYMENT_OBSERVATION_EXPIRED".equals(event.action())).count());
    }

    @Test
    @TestTransaction
    void reconciliationSlaMetricsAggregateOnlyAuthenticatedEnvironmentScope() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID(), outsideEnv = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "sla-metrics-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "f".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtime.id(), "sla-metrics-" + UUID.randomUUID(),
                "metrics.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");
        var outsideEnrollment = registry.issueEnrollmentToken(org, outsideEnv, Duration.ofHours(1));
        var outsideRegistration = registry.enroll(outsideEnrollment.token(),
                "sla-metrics-outside-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var outsideRuntime = registry.authenticate(outsideRegistration.registrationToken());
        var outside = deployments.create(org, Set.of(outsideEnv), outsideRuntime.id(),
                "sla-metrics-outside-" + UUID.randomUUID(), "outside-metrics.flow", "1.0.0",
                digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(outsideEnv), outside.id(), "PRESENT", "test-operator");

        Instant now = Instant.now();
        DeploymentReconciliationEntity row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        row.updatedAt = now.minusSeconds(600);
        row.slaStatus = "WARNING";
        row.slaEvaluatedAt = now;
        row.persist();
        DeploymentReconciliationEntity outsideRow = DeploymentReconciliationEntity.find("deploymentId", outside.id()).firstResult();
        outsideRow.updatedAt = now.minusSeconds(1200);
        outsideRow.slaStatus = "CRITICAL";
        outsideRow.slaEvaluatedAt = now;
        outsideRow.persist();

        audit.append(org, env, "console:test", "RECONCILIATION_SLA_UNINITIALIZED_TO_WARNING",
                "DEPLOYMENT", deployment.id().toString(), now, "SUCCESS");
        audit.append(org, outsideEnv, "console:test", "RECONCILIATION_SLA_UNINITIALIZED_TO_CRITICAL",
                "DEPLOYMENT", outside.id().toString(), now, "SUCCESS");

        var metrics = reconciliation.reconciliationSlaMetrics(org, Set.of(env), now);
        assertEquals(1L, metrics.unresolved());
        assertEquals(0L, metrics.fresh());
        assertEquals(1L, metrics.warning());
        assertEquals(0L, metrics.critical());
        assertEquals(0L, metrics.transitionsToHealthy());
        assertEquals(1L, metrics.transitionsToWarning());
        assertEquals(0L, metrics.transitionsToCritical());
        assertEquals(300L, metrics.warningThresholdSeconds());
        assertEquals(900L, metrics.criticalThresholdSeconds());
        assertEquals("config-v1", metrics.policyVersion());

        var empty = reconciliation.reconciliationSlaMetrics(org, Set.of(), now);
        assertEquals(0L, empty.unresolved());
        assertEquals(0L, empty.transitionsToWarning());
    }

    @Test
    @TestTransaction
    void reconciliationSlaTransitionsAreDurableDeduplicatedAndScoped() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID(), outsideEnv = UUID.randomUUID();
        var enrollment = registry.issueEnrollmentToken(org, env, Duration.ofHours(1));
        var enrolled = registry.enroll(enrollment.token(), "sla-transition-" + UUID.randomUUID(), "1.2.0", null, null, null);
        var runtime = registry.authenticate(enrolled.registrationToken());
        String digest = "sha256:" + "d".repeat(64);
        var deployment = deployments.create(org, Set.of(env), runtime.id(), "sla-transition-" + UUID.randomUUID(),
                "sla-transition.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(env), deployment.id(), "PRESENT", "test-operator");
        DeploymentReconciliationEntity row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        Instant now = Instant.now();
        row.updatedAt = now.minusSeconds(600);
        row.persist();

        assertEquals(1, reconciliation.evaluateSlaTransitions(now));
        row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        assertEquals("WARNING", row.slaStatus);
        assertEquals(now.truncatedTo(ChronoUnit.MICROS), row.slaEvaluatedAt);
        assertEquals(0, reconciliation.evaluateSlaTransitions(now.plusSeconds(10)));

        row.updatedAt = now.minusSeconds(1200);
        row.persist();
        assertEquals(1, reconciliation.evaluateSlaTransitions(now.plusSeconds(20)));
        row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        assertEquals("CRITICAL", row.slaStatus);

        row.updatedAt = now.plusSeconds(20);
        row.persist();
        assertEquals(1, reconciliation.evaluateSlaTransitions(now.plusSeconds(30)));
        row = DeploymentReconciliationEntity.find("deploymentId", deployment.id()).firstResult();
        assertEquals("HEALTHY", row.slaStatus);

        var outsideEnrollment = registry.issueEnrollmentToken(org, outsideEnv, Duration.ofHours(1));
        var outsideRegistration = registry.enroll(outsideEnrollment.token(), "sla-transition-outside-" + UUID.randomUUID(),
                "1.2.0", null, null, null);
        var outsideRuntime = registry.authenticate(outsideRegistration.registrationToken());
        var outside = deployments.create(org, Set.of(outsideEnv), outsideRuntime.id(),
                "sla-transition-outside-" + UUID.randomUUID(), "outside.flow", "1.0.0", digest, digest, "test-operator");
        reconciliation.setDesired(org, Set.of(outsideEnv), outside.id(), "PRESENT", "test-operator");
        DeploymentReconciliationEntity outsideRow = DeploymentReconciliationEntity.find("deploymentId", outside.id()).firstResult();
        outsideRow.updatedAt = now.minusSeconds(1200);
        outsideRow.persist();
        assertEquals(1, reconciliation.evaluateSlaTransitions(now.plusSeconds(40)));

        var history = reconciliation.slaHistory(org, Set.of(env), 0, 10);
        assertEquals(3L, history.total());
        assertEquals(3, history.items().size());
        assertEquals("HEALTHY", history.items().get(0).toStatus());
        assertEquals("CRITICAL", history.items().get(1).toStatus());
        assertEquals("WARNING", history.items().get(2).toStatus());
        assertTrue(history.items().stream().allMatch(item -> deployment.id().equals(item.deploymentId())));
        assertEquals(0L, reconciliation.slaHistory(org, Set.of(), 0, 10).total());
    }

    @Test
    void reconciliationSlaPolicyRejectsInvalidThresholds() {
        assertDoesNotThrow(() -> DeploymentReconciliationService.validateSlaThresholds(300, 900));
        assertDoesNotThrow(() -> DeploymentReconciliationService.validateSlaThresholds(60, 3600));
        assertThrows(IllegalStateException.class, () -> DeploymentReconciliationService.validateSlaThresholds(0, 900));
        assertThrows(IllegalStateException.class, () -> DeploymentReconciliationService.validateSlaThresholds(900, 300));
        assertThrows(IllegalStateException.class, () -> DeploymentReconciliationService.validateSlaThresholds(300, 2_592_001));
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


    @Test
    @TestTransaction
    void webhookDeliveryHistoryIsScopedAndRetryPreservesAuditIdentity() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID(), otherEnv = UUID.randomUUID();
        var event = new ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity();
        event.id = UUID.randomUUID();
        event.organizationId = org;
        event.environmentId = env;
        event.auditSequence = 41L;
        event.recordHash = "a".repeat(64);
        event.deploymentId = UUID.randomUUID();
        event.fromStatus = "WARNING";
        event.toStatus = "CRITICAL";
        event.occurredAt = Instant.now().minusSeconds(60);
        event.status = "FAILED";
        event.attemptCount = 10;
        event.createdAt = Instant.now().minusSeconds(30);
        event.lastAttemptAt = Instant.now().minusSeconds(10);
        event.lastFailureCode = "HTTP_503";
        event.persist();

        var hidden = new ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity();
        hidden.id = UUID.randomUUID();
        hidden.organizationId = org;
        hidden.environmentId = otherEnv;
        hidden.auditSequence = 42L;
        hidden.recordHash = "b".repeat(64);
        hidden.deploymentId = UUID.randomUUID();
        hidden.fromStatus = "WARNING";
        hidden.toStatus = "CRITICAL";
        hidden.occurredAt = Instant.now();
        hidden.status = "FAILED";
        hidden.attemptCount = 10;
        hidden.createdAt = Instant.now();
        hidden.persist();

        var page = webhookSink.deliveryHistory(org, Set.of(env), 0, 25);
        assertEquals(1L, page.total());
        assertEquals(event.id, page.items().get(0).id());

        var retried = webhookSink.retryFailed(org, Set.of(env), event.id,
                "test-operator", "Operator verified endpoint recovery");
        assertEquals("PENDING", retried.status());
        assertEquals(0, retried.attemptCount());
        assertNull(retried.failureCode());
        assertNotNull(retried.nextAttemptAt());
        assertEquals(41L, retried.auditSequence());

        var persisted = ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity
                .<ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity>findById(event.id);
        assertEquals("a".repeat(64), persisted.recordHash);
        assertEquals(1L, ai.wayang.console.adapter.persistence.AuditRecordEntity.count(
                "organizationId = ?1 and environmentId = ?2 and action = ?3 and resourceId = ?4",
                org, env, "RECONCILIATION_WEBHOOK_RETRY_REQUESTED", event.id.toString()));

        assertThrows(jakarta.ws.rs.NotFoundException.class,
                () -> webhookSink.retryFailed(org, Set.of(otherEnv), event.id,
                        "test-operator", "Must not cross environment scope"));
        var conflict = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> webhookSink.retryFailed(org, Set.of(env), event.id,
                        "test-operator", "A second retry requires another terminal failure"));
        assertEquals(409, conflict.getResponse().getStatus());
    }


    @Test
    @TestTransaction
    void webhookRetentionRemovesOnlyOldTerminalRowsInBoundedBatches() {
        UUID org = UUID.randomUUID(), env = UUID.randomUUID();
        Instant old = Instant.now().minusSeconds(120L * 86400L);
        var delivered = webhookDelivery(org, env, "DELIVERED", old);
        var failed = webhookDelivery(org, env, "FAILED", old);
        var pending = webhookDelivery(org, env, "PENDING", old);
        var delivering = webhookDelivery(org, env, "DELIVERING", old);
        int removed = webhookSink.purgeTerminalBefore(Instant.now().minusSeconds(90L * 86400L), 1);
        assertEquals(1, removed);
        long remainingTerminal = ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity.count(
                "id = ?1", delivered.id) + ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity.count(
                "id = ?1", failed.id);
        assertEquals(1L, remainingTerminal);
        assertEquals(1L, ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity.count("id = ?1", pending.id));
        assertEquals(1L, ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity.count("id = ?1", delivering.id));
        assertEquals(1, webhookSink.purgeTerminalBefore(Instant.now().minusSeconds(90L * 86400L), 10));
    }

    private static ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity webhookDelivery(
            UUID org, UUID env, String status, Instant createdAt) {
        var event = new ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity();
        event.id = UUID.randomUUID();
        event.organizationId = org;
        event.environmentId = env;
        event.auditSequence = Math.abs(UUID.randomUUID().getLeastSignificantBits()) + 1L;
        event.recordHash = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2);
        event.deploymentId = UUID.randomUUID();
        event.fromStatus = "WARNING";
        event.toStatus = "CRITICAL";
        event.occurredAt = createdAt;
        event.status = status;
        event.attemptCount = status.equals("FAILED") ? 10 : 1;
        event.createdAt = createdAt;
        event.deliveredAt = status.equals("DELIVERED") ? createdAt : null;
        event.lastAttemptAt = status.equals("FAILED") ? createdAt : null;
        event.leaseUntil = status.equals("DELIVERING") ? Instant.now().plusSeconds(60) : null;
        event.persist();
        return event;
    }

}
