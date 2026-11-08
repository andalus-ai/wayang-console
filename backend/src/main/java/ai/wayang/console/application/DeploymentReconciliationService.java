package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.DeploymentEntity;
import ai.wayang.console.adapter.persistence.DeploymentReconciliationEntity;
import ai.wayang.console.adapter.persistence.RuntimeInstanceEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;

@ApplicationScoped
public class DeploymentReconciliationService {
    @Inject AuditIntegrityService audit;

    public record ReconciliationView(UUID deploymentId, String desiredState, long generation,
            String observedState, String observedArtifactDigest, UUID observedCommandId,
            long reportSequence, Instant observedAt, Instant receivedAt,
            String reconciliationState, String diagnostic, Instant updatedAt) {}
    public record Observation(String state, String artifactDigest, UUID commandId,
            long sequence, Instant observedAt, String diagnostic) {}

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
        } else if (!row.desiredState.equals(desired)) {
            row.generation++;
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
        row.updatedAt = receivedAt;
        row.persist();
        audit.append(deployment.organizationId, deployment.environmentId, "runtime:" + runtime.id(),
                "DEPLOYMENT_OBSERVATION_ACCEPTED", "DEPLOYMENT", deployment.id.toString(), receivedAt, "SUCCESS");
        return view(row);
    }

    @Transactional
    public ReconciliationView get(UUID organizationId, Set<UUID> environments, UUID deploymentId) {
        scopedForUpdate(deploymentId, organizationId, environments);
        DeploymentReconciliationEntity row = DeploymentReconciliationEntity.find("deploymentId", deploymentId).firstResult();
        if (row == null) throw new NotFoundException("Reconciliation state not found");
        return view(row);
    }

    private static DeploymentEntity scoped(UUID id, UUID organizationId, Set<UUID> environments) {
        DeploymentEntity d = DeploymentEntity.findById(id);
        if (d == null || !organizationId.equals(d.organizationId) || environments == null || !environments.contains(d.environmentId))
            throw new NotFoundException("Deployment not found");
        return d;
    }

    private static String evaluate(String desired, String observed, String expectedDigest, String actualDigest) {
        if ("ABSENT".equals(desired) && "ABSENT".equals(observed)) return "IN_SYNC";
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
        return new ReconciliationView(r.deploymentId, r.desiredState, r.generation, r.observedState,
                r.observedArtifactDigest, r.observedCommandId, r.reportSequence, r.observedAt,
                r.receivedAt, r.reconciliationState, r.diagnostic, r.updatedAt);
    }
}
