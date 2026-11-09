package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.PluginApprovalEntity;
import ai.wayang.console.adapter.persistence.PluginApprovalEventEntity;
import ai.wayang.console.adapter.persistence.PluginCatalogEntryEntity;
import ai.wayang.console.testsupport.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PluginApprovalService.ApprovalView> first = executor.submit(() -> requestAfterBarrier(
                    ready, start, organizationId, environmentId, pluginId, version, "operator-a"));
            Future<PluginApprovalService.ApprovalView> second = executor.submit(() -> requestAfterBarrier(
                    ready, start, organizationId, environmentId, pluginId, version, "operator-b"));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both request workers should be ready");
            start.countDown();

            PluginApprovalService.ApprovalView firstResult = first.get(30, TimeUnit.SECONDS);
            PluginApprovalService.ApprovalView secondResult = second.get(30, TimeUnit.SECONDS);

            assertEquals(catalogEntryId, firstResult.catalogEntryId());
            assertEquals(catalogEntryId, secondResult.catalogEntryId());
            assertEquals("PENDING", firstResult.status());
            assertEquals("PENDING", secondResult.status());
            assertEquals(firstResult.requestedAt(), secondResult.requestedAt());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        AtomicLong approvalCount = new AtomicLong();
        AtomicLong initialEventCount = new AtomicLong();
        QuarkusTransaction.requiringNew().run(() -> {
            approvalCount.set(PluginApprovalEntity.count("catalogEntryId", catalogEntryId));
            initialEventCount.set(PluginApprovalEventEntity.count(
                    "catalogEntryId = ?1 and fromStatus is null and toStatus = ?2",
                    catalogEntryId, "PENDING"));
        });

        assertEquals(1L, approvalCount.get(), "Only one approval row may exist for a catalog version");
        assertEquals(1L, initialEventCount.get(), "Only one initial PENDING event may be recorded");
    }

    private PluginApprovalService.ApprovalView requestAfterBarrier(
            CountDownLatch ready, CountDownLatch start, UUID organizationId, UUID environmentId,
            String pluginId, String version, String actor) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent request start");
        }
        return approvals.request(organizationId, environmentId, pluginId, version, actor);
    }
}
