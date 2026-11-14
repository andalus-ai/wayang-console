package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.DeploymentEntity;
import ai.wayang.console.adapter.persistence.AuditRecordEntity;
import ai.wayang.console.adapter.persistence.ManagementCommandEntity;
import ai.wayang.console.adapter.persistence.DeploymentReconciliationEntity;
import ai.wayang.console.adapter.persistence.RuntimeInstanceEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import io.quarkus.panache.common.Page;

@ApplicationScoped
public class DeploymentReconciliationService {
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_RETRIES = 5;
    @Inject AuditIntegrityService audit;

    public record ReconciliationView(UUID deploymentId, String desiredState, long generation,
            String observedState, String observedArtifactDigest, UUID observedCommandId,
            long reportSequence, Instant observedAt, Instant receivedAt, boolean observationFresh,
            String reconciliationState, String diagnostic, Instant updatedAt,
            int retryCount, Instant nextAttemptAt, Instant lastAttemptAt) {}
    public record Observation(String state, String artifactDigest, UUID commandId,
            long generation, long sequence, Instant observedAt, String diagnostic) {}
    public record ReconciliationQueueItem(UUID deploymentId, String deploymentKey, UUID environmentId,
            UUID runtimeId, String workflowId, String workflowVersion, String deploymentStatus,
            ReconciliationView reconciliation) {}
    public record ReconciliationQueuePage(List<ReconciliationQueueItem> items, long total, int page, int size) {}
    public record AuditEventView(long sequence, Instant occurredAt, String actor, String action,
            String outcome, String recordHash) {}
    public record AuditTimelinePage(List<AuditEventView> items, long total, int page, int size) {}

    @Transactional
    public ReconciliationView setDesired(UUID organizationId, Set<UUID> environments,
            UUID deploymentId, String desiredState, String actor) {
        DeploymentEntity deployment = scopedForUpdate(deploymentId, organizationId, environments);
        String desired = normalize(desiredState, "PRESENT", "ABSENT", "ROLLBACK");
        DeploymentReconciliationEntity row = DeploymentReconciliationEntity
                .<DeploymentReconciliationEntity>find("deploymentId", deploymentId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        Instant now = Instant.now();
        if (row == null) {
            row = new DeploymentReconciliationEntity();
            row.id = UUID.randomUUID(); row.deploymentId = deploymentId;
            row.generation = 1; row.observedState = "UNKNOWN"; row.reportSequence = 0;
            long undeliveredOrActive = ManagementCommandEntity.count(
                    "deploymentId = ?1 and generation = 1 and status in ('PENDING','LEASED','RUNNING')",
                    deployment.id);
            if (!"PRESENT".equals(desired) && undeliveredOrActive > 0) {
                row.generation = 2;
                supersedeOlderCommands(deployment, 1L, 2L, now, actor);
            }
        } else if (!row.desiredState.equals(desired)) {
            long previousGeneration = row.generation;
            row.generation++;
            supersedeOlderCommands(deployment, previousGeneration, row.generation, now, actor);
            row.retryCount = 0;
            row.nextAttemptAt = null;
            row.lastAttemptAt = null;
        }
        row.desiredState = desired;
        row.reconciliationState = evaluate(desired, row.observedState, deployment.artifactDigest, row.observedArtifactDigest);
        row.updatedAt = now;
        row.persist();
        audit.append(deployment.organizationId, deployment.environmentId, actor,
                "DEPLOYMENT_DESIRED_STATE_CHANGED", "DEPLOYMENT", deployment.id.toString(), now, "SUCCESS");
        return view(row);
    }

    @Transactional
    public ReconciliationView report(RuntimeRegistryService.AuthenticatedRuntime runtime,
            UUID deploymentId, Observation observation) {
        if (observation == null) throw new IllegalArgumentException("Observation is required");
        DeploymentEntity deployment = DeploymentEntity.findById(deploymentId, LockModeType.PESSIMISTIC_WRITE);
        if (deployment == null || !runtime.id().equals(deployment.runtimeId)
                || !runtime.organizationId().equals(deployment.organizationId)
                || !runtime.environmentId().equals(deployment.environmentId))
            throw new NotFoundException("Deployment not found for this runtime");
        String state = normalize(observation.state(), "PENDING", "APPLYING", "RUNNING", "FAILED",
                "ABSENT", "ROLLING_BACK");
        if (observation.sequence() < 1) throw new IllegalArgumentException("sequence must be positive");
        if (observation.artifactDigest() != null && !observation.artifactDigest().matches("(?i)sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("artifactDigest must be sha256:<64 hex chars>");
        String diagnostic = bounded(observation.diagnostic(), 1000);
        Instant observedAt = observation.observedAt() == null ? Instant.now() : observation.observedAt();
        if (observedAt.isAfter(Instant.now().plusSeconds(60)))
            throw new IllegalArgumentException("observedAt cannot be more than 60 seconds in the future");

        DeploymentReconciliationEntity row = DeploymentReconciliationEntity
                .<DeploymentReconciliationEntity>find("deploymentId", deploymentId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (row == null) {
            row = new DeploymentReconciliationEntity();
            row.id = UUID.randomUUID(); row.deploymentId = deploymentId;
            row.desiredState = "PRESENT"; row.generation = 1; row.reportSequence = 0;
        }
        if (observation.generation() < 1 || observation.generation() != row.generation) {
            throw new WebApplicationException("Observation generation does not match current desired state", 409);
        }
        if (observation.sequence() <= row.reportSequence) {
            throw new WebApplicationException("Observation sequence is stale or already accepted", 409);
        }
        Instant receivedAt = Instant.now();
        row.observedState = state;
        row.observedArtifactDigest = observation.artifactDigest() == null ? null : observation.artifactDigest().toLowerCase();
        row.observedCommandId = observation.commandId();
        row.reportSequence = observation.sequence();
        row.observedAt = observedAt;
        row.receivedAt = receivedAt;
        row.diagnostic = diagnostic;
        row.reconciliationState = evaluate(row.desiredState, state, deployment.artifactDigest, row.observedArtifactDigest);
        row.retryCount = 0;
        row.nextAttemptAt = null;
        row.lastAttemptAt = null;
        row.updatedAt = receivedAt;
        row.persist();
        audit.append(deployment.organizationId, deployment.environmentId, "runtime:" + runtime.id(),
                "DEPLOYMENT_OBSERVATION_ACCEPTED", "DEPLOYMENT", deployment.id.toString(), receivedAt, "SUCCESS");
        return view(row);
    }


    /**
     * Re-evaluates previously healthy or drifted PRESENT deployments after their last
     * authenticated runtime observation expires. This is deliberately non-destructive:
     * it marks the control-plane view as waiting and lets the existing bounded retry
     * policy decide whether another apply command is safe.
     *
     * Candidate IDs are read without locks, then each deployment is locked before its
     * reconciliation row to preserve the global deployment -> reconciliation lock order.
     */
    @Transactional
    public int reconcileExpiredObservations(Instant now) {
        Instant deadline = now.minus(OBSERVATION_TIMEOUT);
        List<UUID> candidates = DeploymentReconciliationEntity.<DeploymentReconciliationEntity>find(
                "desiredState = 'PRESENT' and receivedAt is not null and receivedAt <= ?1 "
                        + "and reconciliationState in ('IN_SYNC','DRIFTED') order by receivedAt",
                deadline).range(0, 99).list().stream().map(row -> row.deploymentId).toList();
        int transitioned = 0;
        for (UUID deploymentId : candidates) {
            DeploymentEntity deployment = DeploymentEntity.findById(deploymentId, LockModeType.PESSIMISTIC_WRITE);
            if (deployment == null) continue;
            DeploymentReconciliationEntity row = DeploymentReconciliationEntity
                    .<DeploymentReconciliationEntity>find("deploymentId", deploymentId)
                    .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
            if (row == null || !"PRESENT".equals(row.desiredState)
                    || !Set.of("IN_SYNC", "DRIFTED").contains(row.reconciliationState)
                    || row.receivedAt == null || row.receivedAt.isAfter(deadline)) continue;
            String previousState = row.reconciliationState;
            row.reconciliationState = "WAITING_RUNTIME";
            row.nextAttemptAt = null;
            row.diagnostic = "Runtime observation expired; previous reconciliation state was " + previousState;
            row.updatedAt = now;
            audit.append(deployment.organizationId, deployment.environmentId, "console:reconciliation-worker",
                    "DEPLOYMENT_OBSERVATION_EXPIRED", "DEPLOYMENT", deployment.id.toString(), now, "WARNING");
            transitioned++;
        }
        return transitioned;
    }

    @Transactional
    public int scheduleTimedOutRetries(Instant now) {
        now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant deadline = now.minus(OBSERVATION_TIMEOUT);
        List<DeploymentReconciliationEntity> timedOut = DeploymentReconciliationEntity
                .<DeploymentReconciliationEntity>find(
                        "desiredState = 'PRESENT' and reconciliationState = 'WAITING_RUNTIME' and updatedAt <= ?1 order by updatedAt",
                        deadline)
                .withLock(LockModeType.PESSIMISTIC_WRITE).range(0, 99).list();
        for (DeploymentReconciliationEntity row : timedOut) {
            if (row.retryCount >= MAX_RETRIES) {
                row.reconciliationState = "MANUAL_INTERVENTION";
                row.nextAttemptAt = null;
                row.diagnostic = "Observation timed out after " + MAX_RETRIES + " automatic retries";
            } else {
                row.retryCount++;
                row.reconciliationState = "RETRYING";
                row.nextAttemptAt = now.plus(retryDelay(row.retryCount));
                row.diagnostic = "Runtime observation timed out; retry " + row.retryCount
                        + " scheduled for " + row.nextAttemptAt;
            }
            row.updatedAt = now;
        }
        return timedOut.size();
    }

    @Transactional
    public List<UUID> dueRetryDeploymentIds(Instant now) {
        return DeploymentReconciliationEntity.<DeploymentReconciliationEntity>list(
                "reconciliationState = 'RETRYING' and desiredState = 'PRESENT' and nextAttemptAt <= ?1 order by nextAttemptAt",
                now).stream().map(row -> row.deploymentId).limit(100).toList();
    }

    static Duration retryDelay(int retryCount) {
        long seconds = Math.min(900L, 30L * (1L << Math.max(0, Math.min(retryCount - 1, 10))));
        return Duration.ofSeconds(seconds);
    }

    /**
     * Tenant/environment-scoped operational queue. The scope is applied through the
     * deployment relation so reconciliation rows cannot be enumerated cross-tenant.
     */
    @Transactional
    public ReconciliationQueuePage listQueue(UUID organizationId, Set<UUID> environments,
            String state, int page, int size) {
        validatePage(page, size);
        if (environments == null || environments.isEmpty())
            return new ReconciliationQueuePage(List.of(), 0, page, size);
        String normalizedState = state == null || state.isBlank() ? null
                : normalize(state, "IN_SYNC", "DRIFTED", "WAITING_RUNTIME", "RETRYING", "MANUAL_INTERVENTION");
        String base = "deploymentId in (select d.id from DeploymentEntity d "
                + "where d.organizationId = ?1 and d.environmentId in ?2)";
        if (normalizedState != null) base += " and reconciliationState = ?3";
        String ordered = base + " order by updatedAt desc, deploymentId asc";
        var query = normalizedState == null
                ? DeploymentReconciliationEntity.find(ordered, organizationId, environments)
                : DeploymentReconciliationEntity.find(ordered, organizationId, environments, normalizedState);
        long total = query.count();
        List<DeploymentReconciliationEntity> rows = query.page(Page.of(page, size)).list();
        if (rows.isEmpty()) return new ReconciliationQueuePage(List.of(), total, page, size);
        List<UUID> ids = rows.stream().map(row -> row.deploymentId).toList();
        Map<UUID, DeploymentEntity> deploymentsById = new HashMap<>();
        DeploymentEntity.<DeploymentEntity>list("id in ?1", ids)
                .forEach(deployment -> deploymentsById.put(deployment.id, deployment));
        List<ReconciliationQueueItem> items = rows.stream().map(row -> {
            DeploymentEntity deployment = deploymentsById.get(row.deploymentId);
            if (deployment == null) return null;
            return new ReconciliationQueueItem(deployment.id, deployment.deploymentId,
                    deployment.environmentId, deployment.runtimeId, deployment.workflowId,
                    deployment.workflowVersion, deployment.status, view(row));
        }).filter(java.util.Objects::nonNull).toList();
        return new ReconciliationQueuePage(items, total, page, size);
    }

    /** Read-only audit timeline backed by the existing tamper-evident audit chain. */
    @Transactional
    public AuditTimelinePage auditTimeline(UUID organizationId, Set<UUID> environments,
            UUID deploymentId, int page, int size) {
        validatePage(page, size);
        scoped(deploymentId, organizationId, environments);
        String resourceId = deploymentId.toString();
        String filter = "organizationId = ?1 and environmentId in ?2 and resourceType = 'DEPLOYMENT' "
                + "and resourceId = ?3";
        long total = AuditRecordEntity.count(filter, organizationId, environments, resourceId);
        List<AuditRecordEntity> records = AuditRecordEntity.<AuditRecordEntity>find(
                filter + " order by sequenceNo desc", organizationId, environments, resourceId)
                .page(Page.of(page, size)).list();
        List<AuditEventView> items = records.stream().map(record -> new AuditEventView(
                record.sequenceNo == null ? 0L : record.sequenceNo, record.occurredAt,
                record.actorSubject, record.action, record.outcome, record.recordHash)).toList();
        return new AuditTimelinePage(items, total, page, size);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("page must be >= 0 and size must be 1..100");
    }

    /**
     * Commands that have not left the queue can be safely closed. A command already
     * leased or running receives a cooperative supersession request and remains visible
     * until the runtime acknowledges or its lease expires.
     */
    private void supersedeOlderCommands(DeploymentEntity deployment, long oldGeneration,
            long newGeneration, Instant now, String actor) {
        List<ManagementCommandEntity> stale = ManagementCommandEntity.<ManagementCommandEntity>find(
                "deploymentId = ?1 and generation <= ?2 and status in ('PENDING','LEASED','RUNNING')",
                deployment.id, oldGeneration).withLock(LockModeType.PESSIMISTIC_WRITE).list();
        for (ManagementCommandEntity command : stale) {
            command.supersededByGeneration = newGeneration;
            command.supersededAt = now;
            if ("PENDING".equals(command.status)) {
                command.status = "SUPERSEDED";
                command.completedAt = now;
                command.resultMessage = "Superseded before delivery by desired generation " + newGeneration;
                audit.append(deployment.organizationId, deployment.environmentId, actor,
                        "DEPLOYMENT_COMMAND_SUPERSEDED", "MANAGEMENT_COMMAND", command.id.toString(), now, "SUCCESS");
            } else {
                command.status = "SUPERSESSION_REQUESTED";
                command.resultMessage = "Runtime must stop or report completion; superseded by desired generation " + newGeneration;
                audit.append(deployment.organizationId, deployment.environmentId, actor,
                        "DEPLOYMENT_COMMAND_SUPERSESSION_REQUESTED", "MANAGEMENT_COMMAND",
                        command.id.toString(), now, "WARNING");
            }
        }
    }

    /** Operator-authorized retry after a runtime failure or exhausted automatic recovery. */
    @Transactional
    public ReconciliationView requestOperatorRetry(UUID organizationId, Set<UUID> environments,
            UUID deploymentId, String actor, String reason) {
        String rationale = bounded(reason, 500);
        if (rationale == null || rationale.isBlank())
            throw new IllegalArgumentException("A non-empty retry reason is required");
        DeploymentEntity deployment = scopedForUpdate(deploymentId, organizationId, environments);
        DeploymentReconciliationEntity row = DeploymentReconciliationEntity
                .<DeploymentReconciliationEntity>find("deploymentId", deploymentId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (row == null) throw new NotFoundException("Reconciliation state not found");
        if (!"PRESENT".equals(row.desiredState))
            throw new WebApplicationException("Only PRESENT deployments can be retried", 409);
        if (!"FAILED".equals(row.observedState) && !"MANUAL_INTERVENTION".equals(row.reconciliationState))
            throw new WebApplicationException("Operator retry requires a FAILED observation or manual-intervention state", 409);
        if (deployment.runtimeId == null)
            throw new WebApplicationException("Deployment has no target runtime", 409);
        long unresolvedSupersessions = ManagementCommandEntity.count(
                "deploymentId = ?1 and status in ('SUPERSESSION_REQUESTED','SUPERSESSION_UNCONFIRMED')",
                deploymentId);
        if (unresolvedSupersessions > 0)
            throw new WebApplicationException("An older command has unconfirmed supersession; resolve it before retrying", 409);
        Instant now = Instant.now();
        Instant cooldownBoundary = now.minusSeconds(30);
        AuditRecordEntity previousRetry = AuditRecordEntity.<AuditRecordEntity>find(
                "organizationId = ?1 and environmentId = ?2 and resourceType = 'DEPLOYMENT' "
                        + "and resourceId = ?3 and action = 'DEPLOYMENT_OPERATOR_RETRY_REQUESTED' "
                        + "order by occurredAt desc",
                deployment.organizationId, deployment.environmentId, deployment.id.toString()).firstResult();
        if (previousRetry != null && previousRetry.occurredAt.isAfter(cooldownBoundary))
            throw new WebApplicationException("Operator retry is cooling down; wait 30 seconds between requests", 429);
        long retriesInWindow = AuditRecordEntity.count(
                "organizationId = ?1 and environmentId = ?2 and resourceType = 'DEPLOYMENT' "
                        + "and resourceId = ?3 and action = 'DEPLOYMENT_OPERATOR_RETRY_REQUESTED' "
                        + "and occurredAt >= ?4",
                deployment.organizationId, deployment.environmentId, deployment.id.toString(), now.minus(Duration.ofHours(1)));
        if (retriesInWindow >= 5)
            throw new WebApplicationException("Operator retry limit reached; maximum is 5 per deployment per hour", 429);
        row.retryCount = 0;
        row.nextAttemptAt = now;
        row.reconciliationState = "RETRYING";
        row.diagnostic = "Operator retry requested: " + rationale;
        row.updatedAt = now;
        row.persist();
        audit.append(deployment.organizationId, deployment.environmentId, actor,
                "DEPLOYMENT_OPERATOR_RETRY_REQUESTED", "DEPLOYMENT", deployment.id.toString(), now, "SUCCESS");
        return view(row);
    }

    @Transactional
    public ReconciliationView get(UUID organizationId, Set<UUID> environments, UUID deploymentId) {
        scoped(deploymentId, organizationId, environments);
        DeploymentReconciliationEntity row = DeploymentReconciliationEntity.find("deploymentId", deploymentId).firstResult();
        if (row == null) throw new NotFoundException("Reconciliation state not found");
        return view(row);
    }

    private static DeploymentEntity scopedForUpdate(UUID id, UUID organizationId, Set<UUID> environments) {
        DeploymentEntity d = DeploymentEntity.findById(id, LockModeType.PESSIMISTIC_WRITE);
        verifyScope(d, organizationId, environments);
        return d;
    }

    private static DeploymentEntity scoped(UUID id, UUID organizationId, Set<UUID> environments) {
        DeploymentEntity d = DeploymentEntity.findById(id);
        verifyScope(d, organizationId, environments);
        return d;
    }

    private static void verifyScope(DeploymentEntity d, UUID organizationId, Set<UUID> environments) {
        if (d == null || !organizationId.equals(d.organizationId) || environments == null || !environments.contains(d.environmentId))
            throw new NotFoundException("Deployment not found");
    }

    private static String evaluate(String desired, String observed, String expectedDigest, String actualDigest) {
        if ("ABSENT".equals(desired) && "ABSENT".equals(observed)) return "IN_SYNC";
        if ("FAILED".equals(observed)) return "MANUAL_INTERVENTION";
        if ("PRESENT".equals(desired) && "RUNNING".equals(observed))
            return expectedDigest != null && expectedDigest.equalsIgnoreCase(actualDigest) ? "IN_SYNC" : "DRIFTED";
        if ("ROLLBACK".equals(desired)) return "WAITING_RUNTIME";
        if ("UNKNOWN".equals(observed) || "PENDING".equals(observed) || "APPLYING".equals(observed) || "ROLLING_BACK".equals(observed))
            return "WAITING_RUNTIME";
        return "DRIFTED";
    }

    private static String normalize(String value, String... allowed) {
        if (value == null) throw new IllegalArgumentException("state is required");
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(normalized)) return normalized;
        throw new IllegalArgumentException("Unsupported state: " + value);
    }
    private static String bounded(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("diagnostic exceeds " + max + " characters");
        if (normalized.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("diagnostic contains control characters");
        return normalized;
    }
    private static ReconciliationView view(DeploymentReconciliationEntity r) {
        boolean fresh = r.receivedAt != null && r.receivedAt.isAfter(Instant.now().minus(OBSERVATION_TIMEOUT));
        return new ReconciliationView(r.deploymentId, r.desiredState, r.generation, r.observedState,
                r.observedArtifactDigest, r.observedCommandId, r.reportSequence, r.observedAt,
                r.receivedAt, fresh, r.reconciliationState, r.diagnostic, r.updatedAt,
                r.retryCount, r.nextAttemptAt, r.lastAttemptAt);
    }
}
