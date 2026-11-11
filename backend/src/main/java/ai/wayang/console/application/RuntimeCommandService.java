package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.DeploymentEntity;
import ai.wayang.console.adapter.persistence.DeploymentCapabilityRequirementEntity;
import ai.wayang.console.adapter.persistence.ManagementCommandEntity;
import ai.wayang.console.adapter.persistence.DeploymentReconciliationEntity;
import ai.wayang.console.adapter.persistence.RuntimeInstanceEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.panache.common.Page;
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
    public record DeploymentCommandView(UUID commandId, long generation, Long supersededByGeneration,
            String commandType, String status, Instant createdAt, Instant lastDeliveredAt,
            Instant acknowledgedAt, Instant completedAt, Instant supersededAt, Instant expiresAt,
            int attemptCount, String resultMessage) {}
    public record DeploymentCommandPage(List<DeploymentCommandView> items, long total, int page, int size) {}

    @Transactional
    public CommandView dispatch(UUID organizationId, Set<UUID> environments,
            UUID deploymentId, String actor) {
        DeploymentEntity deployment = scopedDeployment(organizationId, environments, deploymentId, true);
        DeploymentReconciliationEntity reconciliation = DeploymentReconciliationEntity
                .<DeploymentReconciliationEntity>find("deploymentId", deployment.id)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        long generation = reconciliation == null ? 1L : reconciliation.generation;
        if (reconciliation != null && !"PRESENT".equals(reconciliation.desiredState))
            throw new WebApplicationException("Deployment desired state is not PRESENT", 409);
        String idempotencyKey = "deployment:" + deployment.id + ":generation:" + generation + ":apply:v1";
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
        command.generation = generation;
        command.idempotencyKey = idempotencyKey;
        command.commandType = "APPLY_DEPLOYMENT";
        command.typedPayload = payload(deployment, generation);
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
        ManagementCommandEntity snapshot = ManagementCommandEntity.findById(commandId);
        if (snapshot == null || !snapshot.runtimeId.equals(runtime.id())
                || !snapshot.organizationId.equals(runtime.organizationId())
                || !snapshot.environmentId.equals(runtime.environmentId()))
            throw new NotFoundException("Command not found");
        // Keep lock order aligned with desired-state changes: deployment, reconciliation, command.
        DeploymentEntity.findById(snapshot.deploymentId, LockModeType.PESSIMISTIC_WRITE);
        DeploymentReconciliationEntity reconciliation = DeploymentReconciliationEntity
                .<DeploymentReconciliationEntity>find("deploymentId", snapshot.deploymentId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        ManagementCommandEntity command = ManagementCommandEntity.findById(commandId, LockModeType.PESSIMISTIC_WRITE);
        if (command == null || !command.runtimeId.equals(runtime.id())
                || !command.organizationId.equals(runtime.organizationId())
                || !command.environmentId.equals(runtime.environmentId()))
            throw new NotFoundException("Command not found");

        String status = required(requestedStatus, "status").toUpperCase();
        if (!Set.of("STARTED", "SUCCEEDED", "FAILED", "SUPERSEDED").contains(status))
            throw new WebApplicationException("status must be STARTED, SUCCEEDED, FAILED, or SUPERSEDED", 400);

        if (isTerminal(command.status)) {
            boolean same = ("SUCCEEDED".equals(status) && Set.of("SUCCEEDED", "STALE_SUCCEEDED").contains(command.status))
                    || ("FAILED".equals(status) && Set.of("FAILED", "STALE_FAILED").contains(command.status))
                    || ("SUPERSEDED".equals(status) && "SUPERSEDED".equals(command.status));
            return new AckView(command.id, command.status, same, command.completedAt, command.verifiedArtifactDigest);
        }
        Instant now = Instant.now();
        if ("SUPERSESSION_REQUESTED".equals(command.status)) {
            if ("STARTED".equals(status))
                throw new WebApplicationException("Command supersession was requested; runtime must stop or report its terminal result", 409);
            if ("SUPERSEDED".equals(status)) {
                command.status = "SUPERSEDED";
                command.completedAt = now;
                command.leaseUntil = null;
                command.resultMessage = bounded(message == null ? "Runtime confirmed command supersession" : message, 1000);
                audit.append(command.organizationId, command.environmentId, "runtime:" + runtime.id(),
                        "DEPLOYMENT_COMMAND_SUPERSESSION_CONFIRMED", "MANAGEMENT_COMMAND", command.id.toString(), now, "SUCCESS");
                return new AckView(command.id, command.status, true, command.completedAt, command.verifiedArtifactDigest);
            }
            // A command that completed while the supersession request was in flight is
            // retained as stale evidence and must not mutate the current deployment.
            if ("SUCCEEDED".equals(status)) {
                DeploymentEntity target = DeploymentEntity.findById(command.deploymentId);
                String verified = verifiedArtifactDigest == null ? "" : verifiedArtifactDigest.trim().toLowerCase();
                if (target == null || !verified.matches("sha256:[0-9a-f]{64}")
                        || !target.artifactDigest.equalsIgnoreCase(verified))
                    throw new WebApplicationException("Successful deployment acknowledgement must include the matching verified artifact digest", 409);
                command.verifiedArtifactDigest = verified;
                command.status = "STALE_SUCCEEDED";
            } else {
                command.status = "STALE_FAILED";
            }
            command.completedAt = now;
            command.leaseUntil = null;
            command.resultMessage = bounded(message, 1000);
            audit.append(command.organizationId, command.environmentId, "runtime:" + runtime.id(),
                    "DEPLOYMENT_COMMAND_COMPLETED_AFTER_SUPERSESSION", "MANAGEMENT_COMMAND",
                    command.id.toString(), now, "WARNING");
            return new AckView(command.id, command.status, true, command.completedAt, command.verifiedArtifactDigest);
        }
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
        if ("SUPERSEDED".equals(status))
            throw new WebApplicationException("Command has no outstanding supersession request", 409);
        if (!"LEASED".equals(command.status) && !"RUNNING".equals(command.status))
            throw new WebApplicationException("Command must be leased before completion", 409);

        if ("SUCCEEDED".equals(status)) {
            DeploymentEntity target = DeploymentEntity.findById(command.deploymentId, LockModeType.PESSIMISTIC_WRITE);
            String verified = verifiedArtifactDigest == null ? "" : verifiedArtifactDigest.trim().toLowerCase();
            if (target == null || !verified.matches("sha256:[0-9a-f]{64}")
                    || !target.artifactDigest.equalsIgnoreCase(verified))
                throw new WebApplicationException("Successful deployment acknowledgement must include the matching verified artifact digest", 409);
            command.verifiedArtifactDigest = verified;
        }
        boolean currentGeneration = reconciliation == null || reconciliation.generation == command.generation;
        command.status = currentGeneration ? status : ("SUCCEEDED".equals(status) ? "STALE_SUCCEEDED" : "STALE_FAILED");
        command.completedAt = now;
        command.leaseUntil = null;
        command.resultMessage = bounded(message, 1000);
        if (currentGeneration) {
            DeploymentEntity deployment = DeploymentEntity.findById(command.deploymentId, LockModeType.PESSIMISTIC_WRITE);
            if (deployment != null && "DEPLOYING".equals(deployment.status)) {
                deployment.status = "SUCCEEDED".equals(status) ? "DEPLOYED" : "FAILED";
                deployment.updatedAt = now;
                deployment.completedAt = now;
                if ("FAILED".equals(status)) deployment.failureReason = bounded(message, 1000);
            }
        }
        audit.append(command.organizationId, command.environmentId, "runtime:" + runtime.id(),
                currentGeneration ? "DEPLOYMENT_COMMAND_" + status : "DEPLOYMENT_COMMAND_COMPLETED_STALE_GENERATION",
                "MANAGEMENT_COMMAND", command.id.toString(), now,
                "FAILED".equals(status) ? "FAILURE" : currentGeneration ? "SUCCESS" : "WARNING");
        return new AckView(command.id, command.status, true, command.completedAt, command.verifiedArtifactDigest);
    }

    private static boolean isTerminal(String status) {
        return Set.of("SUCCEEDED", "FAILED", "EXPIRED", "SUPERSEDED", "STALE_SUCCEEDED",
                "STALE_FAILED", "SUPERSESSION_UNCONFIRMED").contains(status);
    }

    @Transactional
    public DeploymentCommandPage listDeploymentCommands(UUID organizationId, Set<UUID> environments,
            UUID deploymentId, int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("page must be >= 0 and size must be 1..100");
        DeploymentEntity deployment = scopedDeployment(organizationId, environments, deploymentId, false);
        String filter = "deploymentId = ?1 and organizationId = ?2 and environmentId = ?3";
        long total = ManagementCommandEntity.count(filter, deploymentId, organizationId, deployment.environmentId);
        List<DeploymentCommandView> items = ManagementCommandEntity.<ManagementCommandEntity>find(
                filter + " order by createdAt desc, id asc", deploymentId, organizationId, deployment.environmentId)
                .page(Page.of(page, size)).list().stream().map(command -> new DeploymentCommandView(
                        command.id, command.generation, command.supersededByGeneration, command.commandType,
                        command.status, command.createdAt, command.lastDeliveredAt, command.acknowledgedAt,
                        command.completedAt, command.supersededAt, command.expiresAt, command.attemptCount,
                        command.resultMessage)).toList();
        return new DeploymentCommandPage(items, total, page, size);
    }

    @Transactional
    public DeploymentCommandView resolveUnconfirmedSupersession(UUID organizationId, Set<UUID> environments,
            UUID deploymentId, UUID commandId, String actor, String reason) {
        String rationale = required(reason, "reason");
        if (rationale.length() > 500)
            throw new IllegalArgumentException("reason must be 500 characters or fewer");
        DeploymentEntity deployment = scopedDeployment(organizationId, environments, deploymentId, true);
        DeploymentReconciliationEntity reconciliation = DeploymentReconciliationEntity
                .<DeploymentReconciliationEntity>find("deploymentId", deploymentId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        ManagementCommandEntity command = ManagementCommandEntity.findById(commandId, LockModeType.PESSIMISTIC_WRITE);
        if (command == null || !deployment.id.equals(command.deploymentId))
            throw new NotFoundException("Command not found");
        if (!"SUPERSESSION_UNCONFIRMED".equals(command.status)
                || reconciliation == null
                || !java.util.Objects.equals(command.supersededByGeneration, reconciliation.generation))
            throw new WebApplicationException("Command is not an unresolved supersession for the current generation", 409);
        Instant now = Instant.now();
        command.status = "SUPERSEDED";
        command.completedAt = now;
        command.leaseUntil = null;
        command.resultMessage = "Operator verified superseded command is no longer executing: " + bounded(rationale, 500);
        audit.append(deployment.organizationId, deployment.environmentId, actor,
                "DEPLOYMENT_COMMAND_SUPERSESSION_OPERATOR_RESOLVED", "MANAGEMENT_COMMAND",
                command.id.toString(), now, "SUCCESS");
        return new DeploymentCommandView(command.id, command.generation, command.supersededByGeneration,
                command.commandType, command.status, command.createdAt, command.lastDeliveredAt,
                command.acknowledgedAt, command.completedAt, command.supersededAt, command.expiresAt,
                command.attemptCount, command.resultMessage);
    }

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void finalizeUnconfirmedSupersessions() {
        Instant now = Instant.now();
        List<UUID> candidateIds = ManagementCommandEntity.<ManagementCommandEntity>find(
                "status = 'SUPERSESSION_REQUESTED' and (leaseUntil is null or leaseUntil <= ?1 or expiresAt <= ?1) order by supersededAt",
                now).range(0, 199).list().stream().map(command -> command.id).toList();
        for (UUID commandId : candidateIds) {
            ManagementCommandEntity snapshot = ManagementCommandEntity.findById(commandId);
            if (snapshot == null) continue;
            DeploymentEntity.findById(snapshot.deploymentId, LockModeType.PESSIMISTIC_WRITE);
            DeploymentReconciliationEntity reconciliation = DeploymentReconciliationEntity
                    .<DeploymentReconciliationEntity>find("deploymentId", snapshot.deploymentId)
                    .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
            ManagementCommandEntity command = ManagementCommandEntity.findById(commandId, LockModeType.PESSIMISTIC_WRITE);
            if (command == null || !"SUPERSESSION_REQUESTED".equals(command.status)
                    || (command.leaseUntil != null && command.leaseUntil.isAfter(now)
                            && command.expiresAt.isAfter(now))) continue;
            command.status = "SUPERSESSION_UNCONFIRMED";
            command.completedAt = now;
            command.leaseUntil = null;
            command.resultMessage = "Runtime did not confirm supersession before its lease expired; manual verification is required";
            if (reconciliation != null && reconciliation.generation == command.supersededByGeneration) {
                reconciliation.reconciliationState = "MANUAL_INTERVENTION";
                reconciliation.nextAttemptAt = null;
                reconciliation.diagnostic = bounded(command.resultMessage, 1000);
                reconciliation.updatedAt = now;
            }
            audit.append(command.organizationId, command.environmentId, "console:command-dispatcher",
                    "DEPLOYMENT_COMMAND_SUPERSESSION_UNCONFIRMED", "MANAGEMENT_COMMAND",
                    command.id.toString(), now, "WARNING");
        }
    }

    @Transactional
    public boolean dispatchDueRetry(UUID deploymentId, Instant now) {
        DeploymentEntity deployment = DeploymentEntity.findById(deploymentId, LockModeType.PESSIMISTIC_WRITE);
        DeploymentReconciliationEntity reconciliation = DeploymentReconciliationEntity
                .<DeploymentReconciliationEntity>find("deploymentId", deploymentId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (reconciliation == null || !"RETRYING".equals(reconciliation.reconciliationState)
                || reconciliation.nextAttemptAt == null || reconciliation.nextAttemptAt.isAfter(now)) return false;
        if (deployment == null || !"PRESENT".equals(reconciliation.desiredState) || deployment.runtimeId == null) {
            reconciliation.reconciliationState = "MANUAL_INTERVENTION";
            reconciliation.nextAttemptAt = null;
            reconciliation.diagnostic = "Retry cannot be dispatched because the deployment or target runtime is unavailable";
            reconciliation.updatedAt = now;
            return false;
        }
        List<ManagementCommandEntity> active = ManagementCommandEntity.list(
                "deploymentId = ?1 and status in ('PENDING','LEASED','RUNNING','SUPERSESSION_REQUESTED','SUPERSESSION_UNCONFIRMED')", deploymentId);
        if (!active.isEmpty()) {
            reconciliation.nextAttemptAt = now.plusSeconds(30);
            reconciliation.diagnostic = "Retry deferred while an earlier command remains active";
            reconciliation.updatedAt = now;
            return false;
        }
        RuntimeCompatibilityEvaluator.Evaluation evaluation = compatibility.evaluate(deployment);
        if (!evaluation.compatible()) {
            String diagnostics = evaluation.diagnostics().stream()
                    .map(diagnostic -> diagnostic.code() + ": " + diagnostic.message())
                    .collect(java.util.stream.Collectors.joining("; "));
            reconciliation.reconciliationState = "MANUAL_INTERVENTION";
            reconciliation.nextAttemptAt = null;
            reconciliation.diagnostic = "Runtime is no longer compatible: " + bounded(diagnostics, 900);
            reconciliation.updatedAt = now;
            return false;
        }
        long retryScheduleEpochMillis = reconciliation.nextAttemptAt.toEpochMilli();
        String idempotencyKey = "deployment:" + deployment.id + ":generation:" + reconciliation.generation
                + ":retry:" + reconciliation.retryCount + ":scheduled:" + retryScheduleEpochMillis + ":apply:v1";
        ManagementCommandEntity existing = ManagementCommandEntity.find("idempotencyKey", idempotencyKey).firstResult();
        if (existing != null) {
            reconciliation.nextAttemptAt = now.plusSeconds(30);
            reconciliation.diagnostic = "Retry command already exists; waiting for its terminal result";
            reconciliation.updatedAt = now;
            return false;
        }
        ManagementCommandEntity command = new ManagementCommandEntity();
        command.id = UUID.randomUUID();
        command.runtimeId = deployment.runtimeId;
        command.organizationId = deployment.organizationId;
        command.environmentId = deployment.environmentId;
        command.deploymentId = deployment.id;
        command.generation = reconciliation.generation;
        command.idempotencyKey = idempotencyKey;
        command.commandType = "APPLY_DEPLOYMENT";
        command.typedPayload = payload(deployment, reconciliation.generation, reconciliation.retryCount);
        command.status = "PENDING";
        command.attemptCount = 0;
        command.maxAttempts = MAX_ATTEMPTS;
        command.createdAt = now;
        command.expiresAt = now.plus(COMMAND_TTL);
        command.persist();
        deployment.status = "DEPLOYING";
        deployment.updatedAt = now;
        deployment.completedAt = null;
        deployment.failureReason = null;
        reconciliation.reconciliationState = "WAITING_RUNTIME";
        reconciliation.nextAttemptAt = null;
        reconciliation.lastAttemptAt = now;
        reconciliation.diagnostic = "Automatic retry " + reconciliation.retryCount + " queued";
        reconciliation.updatedAt = now;
        audit.append(deployment.organizationId, deployment.environmentId, "console:reconciliation-worker",
                "DEPLOYMENT_RETRY_QUEUED", "MANAGEMENT_COMMAND", command.id.toString(), now, "SUCCESS");
        return true;
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

    private static String payload(DeploymentEntity d, long generation) {
        return payload(d, generation, 0);
    }

    private static String payload(DeploymentEntity d, long generation, int retryAttempt) {
        List<DeploymentCapabilityRequirementEntity> requirements =
                DeploymentCapabilityRequirementEntity.list("deploymentId", d.id);
        String requirementsJson = requirements.stream()
                .map(requirement -> "{\"componentId\":\"" + escape(requirement.componentId)
                        + "\",\"capability\":\"" + escape(requirement.capability)
                        + "\",\"minimumVersion\":" + (requirement.minimumVersion == null
                                ? "null" : "\"" + escape(requirement.minimumVersion) + "\"") + "}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"deploymentRecordId\":\"" + d.id + "\",\"generation\":" + generation + ",\"retryAttempt\":" + retryAttempt + ",\"deploymentId\":\"" + escape(d.deploymentId)
                + "\",\"workflowId\":\"" + escape(d.workflowId) + "\",\"workflowVersion\":\""
                + escape(d.workflowVersion) + "\",\"artifactDigest\":\"" + escape(d.artifactDigest)
                + "\",\"manifestDigest\":\"" + escape(d.manifestDigest)
                + "\",\"artifactUri\":\"/api/v1/runtime/artifacts/" + escape(d.artifactDigest)
                + "\",\"minimumRuntimeVersion\":" + (d.minimumRuntimeVersion == null
                        ? "null" : "\"" + escape(d.minimumRuntimeVersion) + "\"")
                + ",\"requiredCapabilities\":[" + requirementsJson + "]}";
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
