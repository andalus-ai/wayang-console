package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

@ApplicationScoped
public class OperatorFleetService {
    public record RuntimeSummary(UUID id, String instanceId, UUID environmentId, String runtimeVersion,
            String deploymentId, String hostname, String platform, String status, String healthStatus,
            Instant registeredAt, Instant lastSeenAt, Instant leaseExpiresAt) {}
    public record RuntimeDetail(RuntimeSummary runtime, List<CapabilityView> capabilities, List<WorkflowView> workflows) {}
    public record CapabilityView(String componentId, String version, String capability) {}
    public record WorkflowView(String workflowId, String workflowVersion, String definitionDigest, String deploymentId) {}
    public record PageResult(List<RuntimeSummary> items, long total, int page, int size) {}
    public record TokenResult(String token, Instant expiresAt) {}

    @Transactional
    public TokenResult issueToken(UUID organizationId, UUID environmentId, Set<UUID> allowedEnvironments,
                                  java.time.Duration ttl) {
        requireEnvironmentScope(environmentId, allowedEnvironments);
        var issued = new RuntimeRegistryService().issueEnrollmentToken(organizationId, environmentId, ttl);
        return new TokenResult(issued.token(), issued.expiresAt());
    }

    @Transactional
    public void revokeEnrollmentToken(UUID organizationId, UUID tokenId, Set<UUID> environments, String actor) {
        EnrollmentTokenEntity token = EnrollmentTokenEntity.findById(tokenId);
        if (token == null || !token.organizationId.equals(organizationId) || !environments.contains(token.environmentId))
            throw new jakarta.ws.rs.NotFoundException("Enrollment token not found");
        if (token.consumedAt != null) throw new jakarta.ws.rs.ConflictException("Enrollment token has already been consumed");
        if (token.revokedAt == null) token.revokedAt = Instant.now();
        audit(organizationId, token.environmentId, actor, "ENROLLMENT_TOKEN_REVOKED", "ENROLLMENT_TOKEN", token.id.toString());
    }

    @Transactional
    public PageResult list(UUID organizationId, Set<UUID> environments, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be >= 0 and size must be 1..100");
        String query = "organizationId = ?1 and environmentId in ?2";
        List<Object> params = new ArrayList<>(List.of(organizationId, environments));
        if (status != null && !status.isBlank()) {
            query += " and status = ?3";
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        var panache = RuntimeInstanceEntity.find(query, Sort.by("registeredAt", Sort.Direction.Descending), params.toArray());
        long total = panache.count();
        List<RuntimeSummary> items = panache.page(Page.of(page, size)).list().stream()
                .map(entity -> summary((RuntimeInstanceEntity) entity)).toList();
        return new PageResult(items, total, page, size);
    }

    @Transactional
    public RuntimeDetail get(UUID organizationId, Set<UUID> environments, UUID runtimeId) {
        RuntimeInstanceEntity runtime = scopedRuntime(organizationId, environments, runtimeId);
        List<CapabilityView> capabilities = RuntimeCapabilityEntity.<RuntimeCapabilityEntity>list("runtimeId", runtimeId)
                .stream().map(c -> new CapabilityView(c.componentId, c.version, c.capability)).toList();
        List<WorkflowView> workflows = WorkflowReferenceEntity.<WorkflowReferenceEntity>list("runtimeId", runtimeId)
                .stream().map(w -> new WorkflowView(w.workflowId, w.workflowVersion, w.definitionDigest, w.deploymentId)).toList();
        return new RuntimeDetail(summary(runtime), capabilities, workflows);
    }

    @Transactional
    public void revokeRuntime(UUID organizationId, Set<UUID> environments, UUID runtimeId, String actor) {
        RuntimeInstanceEntity runtime = scopedRuntime(organizationId, environments, runtimeId);
        runtime.status = "REVOKED";
        runtime.registrationExpiresAt = Instant.now();
        runtime.leaseExpiresAt = null;
        audit(organizationId, runtime.environmentId, actor, "RUNTIME_REVOKED", "RUNTIME", runtime.id.toString());
    }

    private RuntimeInstanceEntity scopedRuntime(UUID organizationId, Set<UUID> environments, UUID runtimeId) {
        RuntimeInstanceEntity runtime = RuntimeInstanceEntity.findById(runtimeId);
        if (runtime == null || !organizationId.equals(runtime.organizationId) || !environments.contains(runtime.environmentId))
            throw new jakarta.ws.rs.NotFoundException("Runtime not found");
        return runtime;
    }

    private static RuntimeSummary summary(RuntimeInstanceEntity r) {
        return new RuntimeSummary(r.id, r.instanceId, r.environmentId, r.runtimeVersion, r.deploymentId,
                r.hostname, r.platform, r.status, r.healthStatus, r.registeredAt, r.lastSeenAt, r.leaseExpiresAt);
    }

    private static void requireEnvironmentScope(UUID environmentId, Set<UUID> allowed) {
        if (environmentId == null || allowed == null || !allowed.contains(environmentId))
            throw new jakarta.ws.rs.NotFoundException("Environment not found");
    }

    private void audit(UUID org, UUID env, String actor, String action, String type, String id) {
        AuditRecordEntity record = new AuditRecordEntity();
        record.id = UUID.randomUUID();
        record.organizationId = org;
        record.environmentId = env;
        record.actorSubject = actor == null || actor.isBlank() ? "unknown" : actor;
        record.action = action;
        record.resourceType = type;
        record.resourceId = id;
        record.occurredAt = Instant.now();
        record.outcome = "SUCCESS";
        record.persist();
    }
}
