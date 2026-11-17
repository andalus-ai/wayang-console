package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.ReconciliationWebhookCursorEntity;
import ai.wayang.console.adapter.persistence.ReconciliationWebhookDeliveryEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provider-owned durable acceptance store and delivery state for SLA webhooks.
 * The audit chain remains the replay source; this table is the provider's delivery queue.
 */
@ApplicationScoped
public class ReconciliationSlaWebhookSink implements ReconciliationSlaAlertSink {
    public static final String SINK_ID = "wayang-https-webhook";
    private static final int MAX_ATTEMPTS = 10;
    private static final long MAX_BACKOFF_SECONDS = 3600;

    @Inject EntityManager entityManager;
    @Inject AuditIntegrityService audit;

    @Override
    public String sinkId() {
        return SINK_ID;
    }

    @Override
    @Transactional
    public long lastAcceptedSequence(UUID organizationId, UUID environmentId) {
        ReconciliationWebhookCursorEntity cursor = cursor(organizationId, environmentId, LockModeType.NONE);
        return cursor == null ? 0L : cursor.lastAcceptedSequence;
    }

    @Override
    @Transactional
    public void accept(DeploymentReconciliationService.SlaAlertTransition transition,
            long expectedLastAcceptedSequence) {
        if (transition == null || transition.organizationId() == null || transition.environmentId() == null
                || transition.auditSequence() <= 0 || transition.recordHash() == null
                || transition.recordHash().isBlank()) {
            throw new IllegalArgumentException("A persisted scoped audit transition is required");
        }
        // Refuse acceptance until this environment has an explicitly configured destination.
        destination(transition.organizationId(), transition.environmentId());

        ReconciliationWebhookCursorEntity cursor = cursor(
                transition.organizationId(), transition.environmentId(), LockModeType.PESSIMISTIC_WRITE);
        long current = cursor == null ? 0L : cursor.lastAcceptedSequence;
        if (transition.auditSequence() <= current) {
            ReconciliationWebhookDeliveryEntity existing = delivery(
                    transition.organizationId(), transition.environmentId(), transition.auditSequence());
            if (existing != null && existing.recordHash.equals(transition.recordHash())) return;
            throw new IllegalStateException("Webhook cursor already passed a different audit record");
        }
        if (current != expectedLastAcceptedSequence) {
            throw new IllegalStateException("Webhook acceptance cursor changed; replay must retry");
        }
        if (transition.auditSequence() <= expectedLastAcceptedSequence) {
            throw new IllegalArgumentException("Webhook audit sequence must advance the cursor");
        }

        ReconciliationWebhookDeliveryEntity event = new ReconciliationWebhookDeliveryEntity();
        event.id = UUID.randomUUID();
        event.organizationId = transition.organizationId();
        event.environmentId = transition.environmentId();
        event.auditSequence = transition.auditSequence();
        event.recordHash = transition.recordHash();
        event.deploymentId = transition.deploymentId();
        event.fromStatus = transition.fromStatus();
        event.toStatus = transition.toStatus();
        event.occurredAt = transition.occurredAt();
        event.status = "PENDING";
        event.attemptCount = 0;
        event.createdAt = Instant.now();
        event.nextAttemptAt = event.createdAt;
        entityManager.persist(event);

        if (cursor == null) {
            cursor = new ReconciliationWebhookCursorEntity();
            cursor.id = UUID.randomUUID();
            cursor.organizationId = transition.organizationId();
            cursor.environmentId = transition.environmentId();
            cursor.lastAcceptedSequence = transition.auditSequence();
            cursor.updatedAt = Instant.now();
            entityManager.persist(cursor);
        } else {
            cursor.lastAcceptedSequence = transition.auditSequence();
            cursor.updatedAt = Instant.now();
        }
        entityManager.flush();
    }

    @Override
    @Transactional
    public DeliveryStatus status(UUID organizationId, Set<UUID> environments) {
        if (organizationId == null || environments == null || environments.isEmpty()) {
            return new DeliveryStatus(0, 0, 0, 0, null, null, null, null, "HEALTHY");
        }
        List<Object[]> rows = entityManager.createQuery("""
                select d.status, count(d)
                from ReconciliationWebhookDeliveryEntity d
                where d.organizationId = :organizationId and d.environmentId in :environments
                group by d.status
                """, Object[].class)
                .setParameter("organizationId", organizationId)
                .setParameter("environments", environments)
                .getResultList();
        long accepted = 0, pending = 0, delivered = 0, failed = 0;
        for (Object[] row : rows) {
            long count = ((Number) row[1]).longValue();
            accepted += count;
            switch ((String) row[0]) {
                case "PENDING", "DELIVERING" -> pending += count;
                case "DELIVERED" -> delivered += count;
                case "FAILED" -> failed += count;
                default -> { }
            }
        }
        Instant oldestPending = entityManager.createQuery("""
                select min(d.createdAt) from ReconciliationWebhookDeliveryEntity d
                where d.organizationId = :organizationId and d.environmentId in :environments
                  and d.status in ('PENDING','DELIVERING')
                """, Instant.class)
                .setParameter("organizationId", organizationId)
                .setParameter("environments", environments)
                .getSingleResult();
        Instant lastDelivered = entityManager.createQuery("""
                select max(d.deliveredAt) from ReconciliationWebhookDeliveryEntity d
                where d.organizationId = :organizationId and d.environmentId in :environments
                  and d.status = 'DELIVERED'
                """, Instant.class)
                .setParameter("organizationId", organizationId)
                .setParameter("environments", environments)
                .getSingleResult();
        ReconciliationWebhookDeliveryEntity latestFailure = ReconciliationWebhookDeliveryEntity
                .<ReconciliationWebhookDeliveryEntity>find(
                        "organizationId = ?1 and environmentId in ?2 and status = 'FAILED' order by lastAttemptAt desc",
                        organizationId, environments)
                .firstResult();
        Instant lastFailureAt = latestFailure == null ? null : latestFailure.lastAttemptAt;
        String failureCode = latestFailure == null ? null : latestFailure.lastFailureCode;
        String health = failed > 0 ? "DEGRADED" : "HEALTHY";
        return new DeliveryStatus(accepted, pending, delivered, failed,
                oldestPending, lastDelivered, lastFailureAt, failureCode, health);
    }

    @Transactional
    public List<DeliveryClaim> claimDue(Instant now, int limit) {
        // Match PostgreSQL TIMESTAMP WITH TIME ZONE precision so lease fencing survives reloads.
        now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        int bounded = Math.max(1, Math.min(limit, 50));
        List<ReconciliationWebhookDeliveryEntity> due = ReconciliationWebhookDeliveryEntity
                .<ReconciliationWebhookDeliveryEntity>find(
                        "(status = 'PENDING' and nextAttemptAt <= ?1) or (status = 'DELIVERING' and leaseUntil <= ?1) order by createdAt",
                        now)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .range(0, bounded - 1).list();
        Instant lease = now.plusSeconds(60);
        for (var event : due) {
            event.status = "DELIVERING";
            event.attemptCount++;
            event.lastAttemptAt = now;
            event.leaseUntil = lease;
        }
        return due.stream().map(event -> new DeliveryClaim(event.id, event.organizationId,
                event.environmentId, event.deploymentId, event.fromStatus, event.toStatus,
                event.occurredAt, event.auditSequence, event.recordHash, event.attemptCount, lease)).toList();
    }

    @Transactional
    public void complete(DeliveryClaim claim, ReconciliationSlaWebhookTransport.Response response) {
        ReconciliationWebhookDeliveryEntity event = entityManager.find(
                ReconciliationWebhookDeliveryEntity.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (!ownsLease(event, claim)) return;
        event.leaseUntil = null;
        if (response.successful()) {
            event.status = "DELIVERED";
            event.deliveredAt = Instant.now();
            event.nextAttemptAt = null;
            event.lastFailureCode = null;
        } else if (response.retryable() && event.attemptCount < MAX_ATTEMPTS) {
            event.status = "PENDING";
            event.nextAttemptAt = Instant.now().plusSeconds(backoffSeconds(event.attemptCount));
            event.lastFailureCode = "HTTP_" + response.statusCode();
        } else {
            event.status = "FAILED";
            event.nextAttemptAt = null;
            event.lastFailureCode = "HTTP_" + response.statusCode();
        }
    }

    @Transactional
    public void fail(DeliveryClaim claim, String failureCode) {
        ReconciliationWebhookDeliveryEntity event = entityManager.find(
                ReconciliationWebhookDeliveryEntity.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (!ownsLease(event, claim)) return;
        event.leaseUntil = null;
        event.lastFailureCode = safeFailureCode(failureCode);
        if (event.attemptCount < MAX_ATTEMPTS) {
            event.status = "PENDING";
            event.nextAttemptAt = Instant.now().plusSeconds(backoffSeconds(event.attemptCount));
        } else {
            event.status = "FAILED";
            event.nextAttemptAt = null;
        }
    }


    public record DeliveryView(UUID id, long auditSequence, UUID deploymentId, String fromStatus,
            String toStatus, Instant occurredAt, String status, int attemptCount, Instant createdAt,
            Instant nextAttemptAt, Instant lastAttemptAt, Instant deliveredAt, String failureCode,
            String secretVersion) {}

    public record WebhookOperationalMetrics(long accepted, long pending, long delivered, long failed,
            long oldestPendingAgeSeconds, double averageDeliveryLatencySeconds,
            Instant lastDeliveredAt, Instant lastFailureAt, String health) {}

    public record DeliveryPage(List<DeliveryView> items, long total, int page, int size) {}

    @Transactional
    public DeliveryPage deliveryHistory(UUID organizationId, Set<UUID> environments, int page, int size) {
        if (page < 0) throw new jakarta.ws.rs.BadRequestException("page must be zero or greater");
        if (organizationId == null || environments == null || environments.isEmpty()) {
            return new DeliveryPage(List.of(), 0, page, Math.max(1, Math.min(size, 100)));
        }
        int boundedSize = Math.max(1, Math.min(size, 100));
        if (page > Integer.MAX_VALUE / boundedSize) throw new jakarta.ws.rs.BadRequestException("page is too large");
        long total = entityManager.createQuery("""
                select count(d) from ReconciliationWebhookDeliveryEntity d
                where d.organizationId = :organizationId and d.environmentId in :environments
                """, Long.class)
                .setParameter("organizationId", organizationId)
                .setParameter("environments", environments)
                .getSingleResult();
        List<ReconciliationWebhookDeliveryEntity> rows = entityManager.createQuery("""
                select d from ReconciliationWebhookDeliveryEntity d
                where d.organizationId = :organizationId and d.environmentId in :environments
                order by d.createdAt desc, d.auditSequence desc
                """, ReconciliationWebhookDeliveryEntity.class)
                .setParameter("organizationId", organizationId)
                .setParameter("environments", environments)
                .setFirstResult(Math.multiplyExact(page, boundedSize))
                .setMaxResults(boundedSize)
                .getResultList();
        return new DeliveryPage(rows.stream().map(ReconciliationSlaWebhookSink::view).toList(),
                total, page, boundedSize);
    }

    @Transactional
    public DeliveryView retryFailed(UUID organizationId, Set<UUID> environments, UUID deliveryId,
            String actor, String reason) {
        if (organizationId == null || environments == null || environments.isEmpty()) {
            throw new jakarta.ws.rs.NotFoundException("Webhook delivery was not found");
        }
        if (deliveryId == null) throw new jakarta.ws.rs.BadRequestException("deliveryId is required");
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new jakarta.ws.rs.BadRequestException("A retry reason between 1 and 500 characters is required");
        }
        ReconciliationWebhookDeliveryEntity event = entityManager.createQuery("""
                select d from ReconciliationWebhookDeliveryEntity d
                where d.id = :id and d.organizationId = :organizationId
                  and d.environmentId in :environments
                """, ReconciliationWebhookDeliveryEntity.class)
                .setParameter("id", deliveryId)
                .setParameter("organizationId", organizationId)
                .setParameter("environments", environments)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst()
                .orElseThrow(() -> new jakarta.ws.rs.NotFoundException("Webhook delivery was not found"));
        if (!"FAILED".equals(event.status)) {
            throw new jakarta.ws.rs.WebApplicationException("Only terminally failed webhook deliveries can be retried", jakarta.ws.rs.core.Response.Status.CONFLICT);
        }
        Instant now = Instant.now();
        audit.append(event.organizationId, event.environmentId, actor,
                "RECONCILIATION_WEBHOOK_RETRY_REQUESTED", "WEBHOOK_DELIVERY",
                event.id.toString(), now, "SUCCESS");
        // Preserve the original audit identity/idempotency key. This is a new delivery attempt,
        // not a new accepted event and must not move the provider's acceptance cursor.
        event.status = "PENDING";
        event.attemptCount = 0;
        event.nextAttemptAt = now;
        event.lastAttemptAt = null;
        event.leaseUntil = null;
        event.deliveredAt = null;
        event.lastFailureCode = null;
        entityManager.flush();
        return view(event);
    }

    @Transactional
    public void recordSecretVersion(DeliveryClaim claim, String secretVersion) {
        String safeVersion = ReconciliationSlaWebhookSupport.validateSecretVersion(secretVersion);
        ReconciliationWebhookDeliveryEntity event = entityManager.find(
                ReconciliationWebhookDeliveryEntity.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (!ownsLease(event, claim)) return;
        event.lastSecretVersion = safeVersion;
    }

    @Transactional
    public WebhookOperationalMetrics operationalMetrics(UUID organizationId, Set<UUID> environments, Instant now) {
        if (organizationId == null || environments == null || environments.isEmpty()) {
            return new WebhookOperationalMetrics(0, 0, 0, 0, 0, 0d, null, null, "HEALTHY");
        }
        DeliveryStatus current = status(organizationId, environments);
        long oldestAge = current.oldestPendingAt() == null ? 0L
                : Math.max(0L, java.time.Duration.between(current.oldestPendingAt(), now).getSeconds());
        Number average = (Number) entityManager.createNativeQuery("""
                select coalesce(avg(extract(epoch from (delivered_at - created_at))), 0)
                from wc_reconciliation_webhook_delivery
                where organization_id = :organizationId and environment_id in (:environments)
                  and delivery_status = 'DELIVERED' and delivered_at is not null
                """)
                .setParameter("organizationId", organizationId)
                .setParameter("environments", environments)
                .getSingleResult();
        return new WebhookOperationalMetrics(current.accepted(), current.pending(), current.delivered(),
                current.failed(), oldestAge, average.doubleValue(), current.lastDeliveredAt(),
                current.lastFailureAt(), current.health());
    }

    @Transactional
    public int purgeTerminalBefore(Instant cutoff, int limit) {
        if (cutoff == null) throw new IllegalArgumentException("A retention cutoff is required");
        int bounded = Math.max(1, Math.min(limit, 1000));
        List<ReconciliationWebhookDeliveryEntity> expired = entityManager.createQuery("""
                select d from ReconciliationWebhookDeliveryEntity d
                where (d.status = 'DELIVERED' and d.deliveredAt < :cutoff)
                   or (d.status = 'FAILED' and d.lastAttemptAt < :cutoff)
                order by d.createdAt asc
                """, ReconciliationWebhookDeliveryEntity.class)
                .setParameter("cutoff", cutoff)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setMaxResults(bounded)
                .getResultList();
        expired.forEach(entityManager::remove);
        entityManager.flush();
        return expired.size();
    }

    private static DeliveryView view(ReconciliationWebhookDeliveryEntity event) {
        return new DeliveryView(event.id, event.auditSequence, event.deploymentId,
                event.fromStatus, event.toStatus, event.occurredAt, event.status,
                event.attemptCount, event.createdAt, event.nextAttemptAt, event.lastAttemptAt,
                event.deliveredAt, event.lastFailureCode, event.lastSecretVersion);
    }

    public record DeliveryClaim(UUID id, UUID organizationId, UUID environmentId, UUID deploymentId,
            String fromStatus, String toStatus, Instant occurredAt, long auditSequence,
            String recordHash, int attempt, Instant leaseUntil) {}

    public record Destination(URI endpoint, String secretReference, String secretVersion) {}

    public static long backoffSeconds(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 10));
        return Math.min(MAX_BACKOFF_SECONDS, 30L * (1L << exponent));
    }

    private boolean ownsLease(ReconciliationWebhookDeliveryEntity event, DeliveryClaim claim) {
        return event != null && "DELIVERING".equals(event.status)
                && event.attemptCount == claim.attempt()
                && claim.leaseUntil().equals(event.leaseUntil);
    }

    private ReconciliationWebhookCursorEntity cursor(UUID organizationId, UUID environmentId, LockModeType lock) {
        var query = entityManager.createQuery("""
                select c from ReconciliationWebhookCursorEntity c
                where c.organizationId = :organizationId and c.environmentId = :environmentId
                """, ReconciliationWebhookCursorEntity.class)
                .setParameter("organizationId", organizationId)
                .setParameter("environmentId", environmentId);
        if (lock != LockModeType.NONE) query.setLockMode(lock);
        return query.getResultStream().findFirst().orElse(null);
    }

    private ReconciliationWebhookDeliveryEntity delivery(UUID organizationId, UUID environmentId, long sequence) {
        return ReconciliationWebhookDeliveryEntity.<ReconciliationWebhookDeliveryEntity>find(
                "organizationId = ?1 and environmentId = ?2 and auditSequence = ?3",
                organizationId, environmentId, sequence).firstResult();
    }

    public static Destination destination(UUID organizationId, UUID environmentId) {
        Config config = ConfigProvider.getConfig();
        String prefix = "wayang.console.reconciliation.sla.webhook.destinations." + organizationId + "." + environmentId;
        String endpoint = config.getOptionalValue(prefix + ".endpoint", String.class).orElse(null);
        String secretReference = config.getOptionalValue(prefix + ".secret-reference", String.class).orElse(null);
        if (endpoint == null || secretReference == null || secretReference.isBlank()) {
            throw new IllegalStateException("Webhook destination is not configured for this environment");
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Webhook destination is invalid", invalid);
        }
        String allowed = config.getOptionalValue(
                "wayang.console.reconciliation.sla.webhook.allowed-hosts", String.class).orElse("");
        Set<String> allowedHosts = java.util.Arrays.stream(allowed.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        ReconciliationSlaWebhookSupport.validateEndpoint(uri, allowedHosts);
        if (!secretReference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}")) {
            throw new IllegalStateException("Webhook secret reference is invalid");
        }
        String secretVersion = config.getOptionalValue(prefix + ".secret-version", String.class).orElse("legacy");
        secretVersion = ReconciliationSlaWebhookSupport.validateSecretVersion(secretVersion);
        return new Destination(uri, secretReference, secretVersion);
    }

    private static String safeFailureCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_.-]{1,80}")) return "TRANSPORT_FAILURE";
        return value;
    }
}
