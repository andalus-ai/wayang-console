package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.DeploymentEntity;
import ai.wayang.console.adapter.persistence.ManagementCommandEntity;
import ai.wayang.console.adapter.persistence.RuntimeInstanceEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class RuntimeCommandService {
    private static final Duration COMMAND_TTL = Duration.ofMinutes(30);
    private static final Duration DELIVERY_LEASE = Duration.ofSeconds(60);
    private static final Duration EXECUTION_LEASE = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 12;

    @Inject AuditIntegrityService audit;
    @Inject RuntimeCompatibilityEvaluator compatibility;

    public record CommandView(UUID commandId, String idempotencyKey, String commandType,
            String typedPayload, Instant expiresAt, int attemptCount) {}
    public record CommandPage(List<CommandView> commands) {}
    public record AckView(UUID commandId, String status, boolean accepted, Instant completedAt, String verifiedArtifactDigest) {}

    @Transactional
    public CommandView dispatch(UUID organizationId, Set<UUID> environments,
            UUID deploymentId, String actor) {
        DeploymentEntity deployment = scopedDeployment(organizationId, environments, deploymentId, true);
        String idempotencyKey = "deployment:" + deployment.id + ":apply:v1";
        ManagementCommandEntity existing = ManagementCommandEntity.find("idempotencyKey", idempotencyKey).firstResult();
        if (existing != null) return view(existing);
        if (!"READY".equals(deployment.status))
            throw new WebApplicationException("Only READY deployments can be dispatched", 409);
        RuntimeCompatibilityEvaluator.Evaluation evaluation = compatibility.evaluate(deployment);
        if (!evaluation.compatible()) {
            String diagnostics = evaluation.diagnostics().stream()
                    .map(diagnostic -> diagnostic.code() + ": " + diagnostic.message())
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new WebApplicationException("Runtime is not compatible with this deployment: " + diagnostics, 409);
        }

        Instant now = Instant.now();
        ManagementCommandEntity command = new ManagementCommandEntity();
        command.id = UUID.randomUUID();
        command.runtimeId = deployment.runtimeId;
        command.organizationId = deployment.organizationId;
        command.environmentId = deployment.environmentId;
        command.deploymentId = deployment.id;
        command.idempotencyKey = idempotencyKey;
        command.commandType = "APPLY_DEPLOYMENT";
        command.typedPayload = payload(deployment);
        command.status = "PENDING";
        command.attemptCount = 0;
        command.maxAttempts = MAX_ATTEMPTS;
        command.createdAt = now;
        command.expiresAt = now.plus(COMMAND_TTL);
        command.persist();

        deployment.status = "DEPLOYING";
        deployment.updatedAt = now;
        audit.append(organizationId, deployment.environmentId, actor, "DEPLOYMENT_COMMAND_QUEUED",
                "MANAGEMENT_COMMAND", command.id.toString(), now, "SUCCESS");
        return view(command);
    }

    @Transactional
    public CommandPage poll(RuntimeRegistryService.AuthenticatedRuntime runtime, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        Instant now = Instant.now();
        List<ManagementCommandEntity> due = ManagementCommandEntity.find(
                "runtimeId = ?1 and expiresAt > ?2 and (status = 'PENDING' or " +
                "((status = 'LEASED' or status = 'RUNNING') and leaseUntil <= ?2)) order by createdAt",
                runtime.id(), now).withLock(LockModeType.PESSIMISTIC_WRITE).range(0, limit - 1).list();

        List<CommandView> result = due.stream().filter(command -> {
            if (command.attemptCount >= command.maxAttempts) {
                expire(command, now, "Maximum delivery attempts reached");
                return false;
            }
            command.status = "LEASED";
            command.attemptCount++;
            command.lastDeliveredAt = now;
            command.leaseUntil = now.plus(DELIVERY_LEASE);
            return true;
        }).map(RuntimeCommandService::view).toList();

        // Expire stale commands even when they are not returned to the runtime.
        List<ManagementCommandEntity> expired = ManagementCommandEntity.find(
                "runtimeId = ?1 and status in ('PENDING','LEASED','RUNNING') and expiresAt <= ?2",
                runtime.id(), now).withLock(LockModeType.PESSIMISTIC_WRITE).list();
        expired.forEach(command -> expire(command, now, "Command expired before completion"));
        return new CommandPage(result);
    }

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void expireOverdueCommands() {
        Instant now = Instant.now();
        List<ManagementCommandEntity> overdue = ManagementCommandEntity.find(
                "status in ('PENDING','LEASED','RUNNING') and expiresAt <= ?1 order by expiresAt", now)
                .withLock(LockModeType.PESSIMISTIC_WRITE).range(0, 199).list();
        overdue.forEach(command -> expire(command, now, "Command expired before completion"));
    }

    @Transactional
    public AckView acknowledge(RuntimeRegistryService.AuthenticatedRuntime runtime,
            UUID commandId, String requestedStatus, String message, String verifiedArtifactDigest) {
        ManagementCommandEntity command = ManagementCommandEntity.findById(commandId, LockModeType.PESSIMISTIC_WRITE);
        if (command == null || !command.runtimeId.equals(runtime.id())
                || !command.organizationId.equals(runtime.organizationId())
                || !command.environmentId.equals(runtime.environmentId()))
            throw new NotFoundException("Command not found");

        String status = required(requestedStatus, "status").toUpperCase();
        if (!Set.of("STARTED", "SUCCEEDED", "FAILED").contains(status))
            throw new WebApplicationException("status must be STARTED, SUCCEEDED, or FAILED", 400);

        if ("SUCCEEDED".equals(command.status) || "FAILED".equals(command.status) || "EXPIRED".equals(command.status)) {
            boolean same = ("SUCCEEDED".equals(status) && "SUCCEEDED".equals(command.status))
                    || ("FAILED".equals(status) && "FAILED".equals(command.status));
            return new AckView(command.id, command.status, same, command.completedAt, command.verifiedArtifactDigest);
        }
        Instant now = Instant.now();
        if (!command.expiresAt.isAfter(now)) {
            expire(command, now, "Command expired before acknowledgement");
            return new AckView(command.id, command.status, false, command.completedAt, command.verifiedArtifactDigest);
        }
        if ("STARTED".equals(status)) {
            if (!"LEASED".equals(command.status) && !"RUNNING".equals(command.status))
                throw new WebApplicationException("Command is not currently leased to this runtime", 409);
            command.status = "RUNNING";
            command.leaseUntil = now.plus(EXECUTION_LEASE);
            command.acknowledgedAt = command.acknowledgedAt == null ? now : command.acknowledgedAt;
            command.resultMessage = bounded(message, 1000);
            return new AckView(command.id, command.status, true, null, command.verifiedArtifactDigest);
        }

        if (!"LEASED".equals(command.status) && !"RUNNING".equals(command.status))
            throw new WebApplicationException("Command must be leased before completion", 409);
        if ("SUCCEEDED".equals(status)) {
            DeploymentEntity target = DeploymentEntity.findById(command.deploymentId, LockModeType.PESSIMISTIC_WRITE);
            String verified = verifiedArtifactDigest == null ? "" : verifiedArtifactDigest.trim().toLowerCase();
            if (target == null || !verified.matches("sha256:[0-9a-f]{64}")
                    || !target.artifactDigest.equalsIgnoreCase(verified)) {
                throw new WebApplicationException("Successful deployment acknowledgement must include the matching verified artifact digest", 409);
            }
            command.verifiedArtifactDigest = verified;
        }
        command.status = status;
        command.completedAt = now;
        command.leaseUntil = null;
        command.resultMessage = bounded(message, 1000);
        DeploymentEntity deployment = DeploymentEntity.findById(command.deploymentId, LockModeType.PESSIMISTIC_WRITE);
        if (deployment != null && "DEPLOYING".equals(deployment.status)) {
            deployment.status = "SUCCEEDED".equals(status) ? "DEPLOYED" : "FAILED";
            deployment.updatedAt = now;
            deployment.completedAt = now;
            if ("FAILED".equals(status)) deployment.failureReason = bounded(message, 1000);
        }
        audit.append(command.organizationId, command.environmentId, "runtime:" + runtime.id(),
                "DEPLOYMENT_COMMAND_" + status, "MANAGEMENT_COMMAND", command.id.toString(), now,
                "FAILED".equals(status) ? "FAILURE" : "SUCCESS");
        return new AckView(command.id, command.status, true, command.completedAt, command.verifiedArtifactDigest);
    }

    private static DeploymentEntity scopedDeployment(UUID organizationId, Set<UUID> environments,
            UUID deploymentId, boolean lock) {
        DeploymentEntity d = lock
                ? DeploymentEntity.findById(deploymentId, LockModeType.PESSIMISTIC_WRITE)
                : DeploymentEntity.findById(deploymentId);
        if (d == null || !organizationId.equals(d.organizationId)
                || environments == null || !environments.contains(d.environmentId))
            throw new NotFoundException("Deployment not found");
        if (d.runtimeId == null) throw new WebApplicationException("Deployment has no target runtime", 409);
        return d;
    }

    private void expire(ManagementCommandEntity command, Instant now, String reason) {
        command.status = "EXPIRED";
        command.completedAt = now;
        command.leaseUntil = null;
        command.resultMessage = reason;
        DeploymentEntity deployment = DeploymentEntity.findById(command.deploymentId, LockModeType.PESSIMISTIC_WRITE);
        if (deployment != null && "DEPLOYING".equals(deployment.status)) {
            deployment.status = "FAILED";
            deployment.updatedAt = now;
            deployment.completedAt = now;
            deployment.failureReason = reason;
        }
        audit.append(command.organizationId, command.environmentId, "console:command-dispatcher",
                "DEPLOYMENT_COMMAND_EXPIRED", "MANAGEMENT_COMMAND", command.id.toString(), now, "FAILURE");
    }

    private static String payload(DeploymentEntity d) {
        return "{\"deploymentRecordId\":\"" + d.id + "\",\"deploymentId\":\"" + escape(d.deploymentId)
                + "\",\"workflowId\":\"" + escape(d.workflowId) + "\",\"workflowVersion\":\""
                + escape(d.workflowVersion) + "\",\"artifactDigest\":\"" + escape(d.artifactDigest)
                + "\",\"manifestDigest\":\"" + escape(d.manifestDigest) + "\",\"artifactUri\":\"/api/v1/runtime/artifacts/" + escape(d.artifactDigest) + "\"}";
    }
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
    private static CommandView view(ManagementCommandEntity c) {
        return new CommandView(c.id, c.idempotencyKey, c.commandType, c.typedPayload, c.expiresAt, c.attemptCount);
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String bounded(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
