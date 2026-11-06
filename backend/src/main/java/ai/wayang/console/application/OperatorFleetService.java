package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

@ApplicationScoped
public class OperatorFleetService {
    @Inject RuntimeRegistryService registry;
    @Inject AuditIntegrityService auditIntegrity;

    public record RuntimeSummary(UUID id, String instanceId, UUID environmentId, String runtimeVersion,
            String deploymentId, String hostname, String platform, String status, String healthStatus,
            Instant registeredAt, Instant lastSeenAt, Instant leaseExpiresAt) {}
    public record RuntimeDetail(RuntimeSummary runtime, List<CapabilityView> capabilities, List<WorkflowView> workflows) {}
    public record CapabilityView(String componentId, String version, String capability) {}
    public record WorkflowView(String workflowId, String workflowVersion, String definitionDigest, String deploymentId) {}
    public record PageResult(List<RuntimeSummary> items, long total, int page, int size) {}
    public record AuditView(Instant occurredAt, String actorSubject, String action, String resourceType, String resourceId, String outcome) {}
    public record AuditPage(List<AuditView> items, long total, int page, int size) {}
    public record TokenResult(UUID tokenId, String token, Instant expiresAt) {}

    @Transactional
    public TokenResult issueToken(UUID organizationId, UUID environmentId, Set<UUID> allowedEnvironments, Duration ttl, String actor) {
        requireEnvironmentScope(environmentId, allowedEnvironments);
        var issued = registry.issueEnrollmentToken(organizationId, environmentId, ttl);
        auditIntegrity.append(organizationId, environmentId, actor, "ENROLLMENT_TOKEN_ISSUED", "ENROLLMENT_TOKEN", issued.tokenId().toString(), Instant.now(), "SUCCESS");
        return new TokenResult(issued.tokenId(), issued.token(), issued.expiresAt());
    }

    @Transactional
    public void revokeEnrollmentToken(UUID organizationId, UUID tokenId, Set<UUID> environments, String actor) {
        EnrollmentTokenEntity token = EnrollmentTokenEntity.findById(tokenId);
        if (token == null || !token.organizationId.equals(organizationId) || !environments.contains(token.environmentId))
            throw new NotFoundException("Enrollment token not found");
        if (token.consumedAt != null) throw new WebApplicationException("Enrollment token has already been consumed", 409);
        if (token.revokedAt == null) {
            token.revokedAt = Instant.now();
            auditIntegrity.append(organizationId, token.environmentId, actor, "ENROLLMENT_TOKEN_REVOKED", "ENROLLMENT_TOKEN", token.id.toString(), Instant.now(), "SUCCESS");
        }
    }

    @Transactional
    public PageResult list(UUID organizationId, Set<UUID> environments, String status, int page, int size) {
        validatePage(environments, page, size);
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
        if (!"REVOKED".equals(runtime.status)) {
            runtime.status = "REVOKED";
            runtime.registrationExpiresAt = Instant.now();
            runtime.leaseExpiresAt = null;
            auditIntegrity.append(organizationId, runtime.environmentId, actor, "RUNTIME_REVOKED", "RUNTIME", runtime.id.toString(), Instant.now(), "SUCCESS");
        }
    }

    @Transactional
    public RuntimeRegistryService.CredentialRotation rotateCredential(UUID organizationId, Set<UUID> environments,
            UUID runtimeId, String actor) {
        RuntimeInstanceEntity runtime = scopedRuntime(organizationId, environments, runtimeId);
        return registry.rotateCredential(runtime.id, actor);
    }

    @Transactional
    public AuditIntegrityService.VerificationResult verifyAudit(UUID organizationId, Set<UUID> environments,
            UUID environmentId) {
        requireEnvironmentScope(environmentId, environments);
        return auditIntegrity.verify(organizationId, environmentId);
    }

    @Transactional
    public AuditPage listAudit(UUID organizationId, Set<UUID> environments, int page, int size) {
        validatePage(environments, page, size);
        var query = AuditRecordEntity.find("organizationId = ?1 and environmentId in ?2",
                Sort.by("occurredAt", Sort.Direction.Descending), organizationId, environments);
        long total = query.count();
        List<AuditView> items = query.page(Page.of(page, size)).list().stream()
                .map(value -> (AuditRecordEntity) value)
                .map(a -> new AuditView(a.occurredAt, a.actorSubject, a.action, a.resourceType, a.resourceId, a.outcome)).toList();
        return new AuditPage(items, total, page, size);
    }

    private void validatePage(Set<UUID> environments, int page, int size) {
        if (environments == null || environments.isEmpty()) throw new NotFoundException("No environments are available in operator scope");
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be >= 0 and size must be 1..100");
    }

    private RuntimeInstanceEntity scopedRuntime(UUID organizationId, Set<UUID> environments, UUID runtimeId) {
        RuntimeInstanceEntity runtime = RuntimeInstanceEntity.findById(runtimeId);
        if (runtime == null || !organizationId.equals(runtime.organizationId) || !environments.contains(runtime.environmentId))
            throw new NotFoundException("Runtime not found");
        return runtime;
    }

    private static RuntimeSummary summary(RuntimeInstanceEntity r) {
        return new RuntimeSummary(r.id, r.instanceId, r.environmentId, r.runtimeVersion, r.deploymentId,
                r.hostname, r.platform, r.status, r.healthStatus, r.registeredAt, r.lastSeenAt, r.leaseExpiresAt);
    }

    private static void requireEnvironmentScope(UUID environmentId, Set<UUID> allowed) {
        if (environmentId == null || allowed == null || !allowed.contains(environmentId))
            throw new NotFoundException("Environment not found");
    }

}
