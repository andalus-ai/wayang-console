package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.PluginApprovalEntity;
import ai.wayang.console.adapter.persistence.PluginApprovalEventEntity;
import ai.wayang.console.adapter.persistence.PluginCatalogEntryEntity;
import ai.wayang.console.testsupport.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class PluginApprovalConcurrencyIntegrationTest {
    @Inject PluginApprovalService approvals;
    @Inject ArtifactRegistryService artifacts;
    @Inject PublisherSigningKeyTrustService signingKeys;

    @Test
    void simultaneousRequestsCreateOnePendingApprovalAndOneInitialEvent() throws Exception {
        PreparedApproval prepared = prepareCatalogEntry();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PluginApprovalService.ApprovalView> first = executor.submit(() -> requestAfterBarrier(
                    ready, start, prepared, "operator-a"));
            Future<PluginApprovalService.ApprovalView> second = executor.submit(() -> requestAfterBarrier(
                    ready, start, prepared, "operator-b"));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both request workers should be ready");
            start.countDown();

            PluginApprovalService.ApprovalView firstResult = first.get(30, TimeUnit.SECONDS);
            PluginApprovalService.ApprovalView secondResult = second.get(30, TimeUnit.SECONDS);

            assertEquals(prepared.catalogEntryId(), firstResult.catalogEntryId());
            assertEquals(prepared.catalogEntryId(), secondResult.catalogEntryId());
            assertEquals("PENDING", firstResult.status());
            assertEquals("PENDING", secondResult.status());
            assertEquals(firstResult.requestedAt(), secondResult.requestedAt());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertPersistedCounts(prepared.catalogEntryId(), 1L, 1L, 0L);
    }

    @Test
    void concurrentRetryAndApprovalDecisionLeaveOneApprovedRecordAndTwoEvents() throws Exception {
        PreparedApproval prepared = prepareCatalogEntry();
        PluginApprovalService.ApprovalView initial = approvals.request(
                prepared.organizationId(), prepared.environmentId(), prepared.pluginId(),
                prepared.version(), "operator-a");
        assertEquals("PENDING", initial.status());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RetryOutcome> retry = executor.submit(() -> retryAfterBarrier(ready, start, prepared));
            Future<PluginApprovalService.ApprovalView> decision = executor.submit(
                    () -> decideAfterBarrier(ready, start, prepared));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both race workers should be ready");
            start.countDown();

            RetryOutcome retryOutcome = retry.get(30, TimeUnit.SECONDS);
            PluginApprovalService.ApprovalView decisionResult = decision.get(30, TimeUnit.SECONDS);

            assertTrue(retryOutcome.pendingReturned() || retryOutcome.conflictReturned(),
                    "Retry must either observe PENDING or receive the expected final-state conflict");
            assertEquals("APPROVED", decisionResult.status());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        PluginApprovalService.ApprovalView persisted = approvals.get(
                prepared.organizationId(), prepared.environmentId(), prepared.pluginId(), prepared.version());
        assertEquals("APPROVED", persisted.status());
        assertEquals("reviewer", persisted.decidedBy());

        assertPersistedCounts(prepared.catalogEntryId(), 1L, 1L, 1L);
        var history = approvals.history(prepared.organizationId(), prepared.environmentId(),
                prepared.pluginId(), prepared.version());
        assertEquals(2, history.size(), "Only the initial request and final approval transitions should exist");
        assertNull(history.get(0).fromStatus());
        assertEquals("PENDING", history.get(0).toStatus());
        assertEquals("PENDING", history.get(1).fromStatus());
        assertEquals("APPROVED", history.get(1).toStatus());
    }

    @Test
    void concurrentOpposingDecisionsPersistExactlyOneTerminalOutcome() throws Exception {
        PreparedApproval prepared = prepareCatalogEntry();
        PluginApprovalService.ApprovalView initial = approvals.request(
                prepared.organizationId(), prepared.environmentId(), prepared.pluginId(),
                prepared.version(), "operator-a");
        assertEquals("PENDING", initial.status());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<DecisionOutcome> approve = executor.submit(
                    () -> decisionOutcomeAfterBarrier(ready, start, prepared, "reviewer-approve", "APPROVE"));
            Future<DecisionOutcome> reject = executor.submit(
                    () -> decisionOutcomeAfterBarrier(ready, start, prepared, "reviewer-reject", "REJECT"));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both decision workers should be ready");
            start.countDown();

            DecisionOutcome approveOutcome = approve.get(30, TimeUnit.SECONDS);
            DecisionOutcome rejectOutcome = reject.get(30, TimeUnit.SECONDS);
            assertEquals(1, (approveOutcome.succeeded() ? 1 : 0) + (rejectOutcome.succeeded() ? 1 : 0),
                    "Exactly one competing decision must succeed");
            assertEquals(1, (approveOutcome.conflicted() ? 1 : 0) + (rejectOutcome.conflicted() ? 1 : 0),
                    "The losing decision must receive HTTP 409");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        PluginApprovalService.ApprovalView persisted = approvals.get(
                prepared.organizationId(), prepared.environmentId(), prepared.pluginId(), prepared.version());
        assertTrue("APPROVED".equals(persisted.status()) || "REJECTED".equals(persisted.status()),
                "The approval must have exactly one terminal status");
        assertTrue("reviewer-approve".equals(persisted.decidedBy())
                        || "reviewer-reject".equals(persisted.decidedBy()),
                "The persisted decision must belong to one of the competing reviewers");

        assertPersistedCounts(prepared.catalogEntryId(), 1L, 1L,
                "APPROVED".equals(persisted.status()) ? 1L : 0L);
        assertTerminalDecisionCounts(prepared.catalogEntryId(),
                "APPROVED".equals(persisted.status()) ? 1L : 0L,
                "REJECTED".equals(persisted.status()) ? 1L : 0L);

        var history = approvals.history(prepared.organizationId(), prepared.environmentId(),
                prepared.pluginId(), prepared.version());
        assertEquals(2, history.size(), "Only the initial request and winning decision may be recorded");
        assertNull(history.get(0).fromStatus());
        assertEquals("PENDING", history.get(0).toStatus());
        assertEquals("PENDING", history.get(1).fromStatus());
        assertEquals(persisted.status(), history.get(1).toStatus());
        assertEquals(persisted.decidedBy(), history.get(1).actor());
    }

    private DecisionOutcome decisionOutcomeAfterBarrier(
            CountDownLatch ready, CountDownLatch start, PreparedApproval prepared,
            String actor, String action) throws Exception {
        awaitStart(ready, start);
        try {
            PluginApprovalService.ApprovalView result = approvals.decide(
                    prepared.organizationId(), prepared.environmentId(), prepared.pluginId(),
                    prepared.version(), actor,
                    new PluginApprovalService.Decision(action, "Competing decision concurrency test"));
            assertEquals(action.equals("APPROVE") ? "APPROVED" : "REJECTED", result.status());
            return new DecisionOutcome(true, false);
        } catch (WebApplicationException conflict) {
            assertEquals(409, conflict.getResponse().getStatus());
            return new DecisionOutcome(false, true);
        }
    }

    private void assertTerminalDecisionCounts(UUID catalogEntryId,
            long approvedEventsExpected, long rejectedEventsExpected) {
        AtomicLong approvedEventCount = new AtomicLong();
        AtomicLong rejectedEventCount = new AtomicLong();
        QuarkusTransaction.requiringNew().run(() -> {
            approvedEventCount.set(PluginApprovalEventEntity.count(
                    "catalogEntryId = ?1 and fromStatus = ?2 and toStatus = ?3",
                    catalogEntryId, "PENDING", "APPROVED"));
            rejectedEventCount.set(PluginApprovalEventEntity.count(
                    "catalogEntryId = ?1 and fromStatus = ?2 and toStatus = ?3",
                    catalogEntryId, "PENDING", "REJECTED"));
        });
        assertEquals(approvedEventsExpected, approvedEventCount.get(),
                "There must be no duplicate or competing APPROVED transition");
        assertEquals(rejectedEventsExpected, rejectedEventCount.get(),
                "There must be no duplicate or competing REJECTED transition");
        assertEquals(1L, approvedEventCount.get() + rejectedEventCount.get(),
                "Exactly one terminal decision event must be persisted");
    }

    private PreparedApproval prepareCatalogEntry() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        String pluginId = "concurrency-plugin-" + UUID.randomUUID();
        String version = "1.0.0";
        String keyId = "integration-" + UUID.randomUUID();
        byte[] artifactBytes = "plugin-artifact-for-concurrency-test".getBytes(StandardCharsets.UTF_8);
        String artifactDigest = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(artifactBytes));
        String manifestDigest = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("plugin-manifest".getBytes(StandardCharsets.UTF_8)));

        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        signingKeys.register(organizationId, environmentId, "publisher",
                new PublisherSigningKeyTrustService.RegisterCommand(keyId, publicKey));

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update((artifactDigest + "\n" + manifestDigest + "\n" + keyId)
                .getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        artifacts.register(organizationId, environmentId, "publisher", artifactDigest, manifestDigest,
                keyId, signature, new ByteArrayInputStream(artifactBytes));

        UUID catalogEntryId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            PluginCatalogEntryEntity entry = new PluginCatalogEntryEntity();
            entry.id = catalogEntryId;
            entry.organizationId = organizationId;
            entry.environmentId = environmentId;
            entry.pluginId = pluginId;
            entry.pluginVersion = version;
            entry.manifestDigest = manifestDigest;
            entry.metadataJson = "{}";
            entry.metadataDigest = manifestDigest;
            entry.publishedBy = "publisher";
            entry.createdAt = Instant.now();
            entry.persist();
        });
        return new PreparedApproval(organizationId, environmentId, pluginId, version, catalogEntryId);
    }

    private PluginApprovalService.ApprovalView requestAfterBarrier(
            CountDownLatch ready, CountDownLatch start, PreparedApproval prepared, String actor) throws Exception {
        awaitStart(ready, start);
        return approvals.request(prepared.organizationId(), prepared.environmentId(),
                prepared.pluginId(), prepared.version(), actor);
    }

    private RetryOutcome retryAfterBarrier(
            CountDownLatch ready, CountDownLatch start, PreparedApproval prepared) throws Exception {
        awaitStart(ready, start);
        try {
            PluginApprovalService.ApprovalView result = approvals.request(prepared.organizationId(),
                    prepared.environmentId(), prepared.pluginId(), prepared.version(), "operator-b");
            assertEquals("PENDING", result.status());
            return new RetryOutcome(true, false);
        } catch (WebApplicationException conflict) {
            assertEquals(409, conflict.getResponse().getStatus());
            return new RetryOutcome(false, true);
        }
    }

    private PluginApprovalService.ApprovalView decideAfterBarrier(
            CountDownLatch ready, CountDownLatch start, PreparedApproval prepared) throws Exception {
        awaitStart(ready, start);
        return approvals.decide(prepared.organizationId(), prepared.environmentId(),
                prepared.pluginId(), prepared.version(), "reviewer",
                new PluginApprovalService.Decision("APPROVE", "Concurrency integration test approval"));
    }

    private void awaitStart(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent operation start");
        }
    }

    private void assertPersistedCounts(UUID catalogEntryId, long approvalsExpected,
            long initialEventsExpected, long approvalEventsExpected) {
        AtomicLong approvalCount = new AtomicLong();
        AtomicLong initialEventCount = new AtomicLong();
        AtomicLong approvalEventCount = new AtomicLong();
        QuarkusTransaction.requiringNew().run(() -> {
            approvalCount.set(PluginApprovalEntity.count("catalogEntryId", catalogEntryId));
            initialEventCount.set(PluginApprovalEventEntity.count(
                    "catalogEntryId = ?1 and fromStatus is null and toStatus = ?2",
                    catalogEntryId, "PENDING"));
            approvalEventCount.set(PluginApprovalEventEntity.count(
                    "catalogEntryId = ?1 and fromStatus = ?2 and toStatus = ?3",
                    catalogEntryId, "PENDING", "APPROVED"));
        });
        assertEquals(approvalsExpected, approvalCount.get(), "Unexpected number of approval rows");
        assertEquals(initialEventsExpected, initialEventCount.get(), "Unexpected number of initial PENDING events");
        assertEquals(approvalEventsExpected, approvalEventCount.get(), "Unexpected number of approval transition events");
    }

    private record PreparedApproval(UUID organizationId, UUID environmentId, String pluginId,
            String version, UUID catalogEntryId) {}

    private record RetryOutcome(boolean pendingReturned, boolean conflictReturned) {}

    private record DecisionOutcome(boolean succeeded, boolean conflicted) {}
}
