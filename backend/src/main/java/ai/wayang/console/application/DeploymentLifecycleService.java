package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.DeploymentEntity;
import ai.wayang.console.adapter.persistence.DeploymentCapabilityRequirementEntity;
import ai.wayang.console.adapter.persistence.RuntimeInstanceEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class DeploymentLifecycleService {
    @Inject AuditIntegrityService audit;
    @Inject ArtifactRegistryService artifacts;
    @Inject @ConfigProperty(name = "wayang.console.artifacts.require-registered", defaultValue = "false") boolean requireRegisteredArtifact;

    public record DeploymentView(UUID id, String deploymentId, UUID runtimeId, String workflowId,
            String workflowVersion, String artifactDigest, String manifestDigest, String status,
            Instant createdAt, Instant updatedAt, Instant completedAt, String failureReason) {}
    public record DeploymentPage(List<DeploymentView> items, long total, int page, int size) {}
    public record CapabilityRequirement(String componentId, String capability, String minimumVersion) {}

    @Transactional
    public DeploymentView create(UUID organizationId, Set<UUID> environments, UUID runtimeId,
            String deploymentId, String workflowId, String workflowVersion, String artifactDigest,
            String manifestDigest, String actor) {
        return create(organizationId, environments, runtimeId, deploymentId, workflowId, workflowVersion,
                artifactDigest, manifestDigest, List.of(), actor);
    }

    @Transactional
    public DeploymentView create(UUID organizationId, Set<UUID> environments, UUID runtimeId,
            String deploymentId, String workflowId, String workflowVersion, String artifactDigest,
            String manifestDigest, List<CapabilityRequirement> requirements, String actor) {
        RuntimeInstanceEntity runtime = RuntimeInstanceEntity.findById(runtimeId, LockModeType.PESSIMISTIC_READ);
        if (runtime == null || !organizationId.equals(runtime.organizationId)
                || environments == null || !environments.contains(runtime.environmentId))
            throw new NotFoundException("Runtime not found");
        required(deploymentId, "deploymentId");
        required(workflowId, "workflowId");
        required(workflowVersion, "workflowVersion");
        digest(artifactDigest, "artifactDigest");
        digest(manifestDigest, "manifestDigest");
        if (requireRegisteredArtifact) artifacts.assertRegistered(organizationId, runtime.environmentId, artifactDigest, manifestDigest);
        List<CapabilityRequirement> safeRequirements = requirements == null ? List.of() : List.copyOf(requirements);
        if (safeRequirements.size() > 100) throw new IllegalArgumentException("At most 100 runtime capability requirements are allowed");
        Set<String> requirementKeys = new HashSet<>();
        for (CapabilityRequirement requirement : safeRequirements) {
            if (requirement == null) throw new IllegalArgumentException("Capability requirement entries must not be null");
            String component = requiredBounded(requirement.componentId(), 180, "componentId");
            String capability = requiredBounded(requirement.capability(), 180, "capability");
            String minimum = requirement.minimumVersion() == null || requirement.minimumVersion().isBlank()
                    ? null : requiredBounded(requirement.minimumVersion(), 80, "minimumVersion");
            if (minimum != null && !minimum.matches("(?i)v?[0-9]+(?:\\.[0-9]+)*"))
                throw new IllegalArgumentException("minimumVersion must be a numeric dotted version");
            if (!requirementKeys.add(component.toLowerCase() + "|" + capability.toLowerCase()))
                throw new IllegalArgumentException("Duplicate capability requirement");
        }
        if (DeploymentEntity.find("organizationId = ?1 and environmentId = ?2 and deploymentId = ?3",
                organizationId, runtime.environmentId, deploymentId).firstResult() != null)
            throw new jakarta.ws.rs.WebApplicationException("Deployment id already exists in this scope", 409);
        Instant now = Instant.now();
        DeploymentEntity d = new DeploymentEntity();
        d.id = UUID.randomUUID(); d.deploymentId = deploymentId.trim(); d.organizationId = organizationId;
        d.environmentId = runtime.environmentId; d.runtimeId = runtime.id; d.workflowId = workflowId.trim();
        d.workflowVersion = workflowVersion.trim(); d.artifactDigest = artifactDigest.toLowerCase();
        d.manifestDigest = manifestDigest.toLowerCase(); d.status = "REQUESTED"; d.requestedBy = required(actor, "actor");
        d.createdAt = now; d.updatedAt = now; d.persist();
        for (CapabilityRequirement requirement : safeRequirements) {
            DeploymentCapabilityRequirementEntity entity = new DeploymentCapabilityRequirementEntity();
            entity.id = UUID.randomUUID(); entity.deploymentId = d.id;
            entity.componentId = requirement.componentId().trim(); entity.capability = requirement.capability().trim();
            entity.minimumVersion = requirement.minimumVersion() == null || requirement.minimumVersion().isBlank()
                    ? null : requirement.minimumVersion().trim();
            entity.persist();
        }
        audit.append(organizationId, runtime.environmentId, actor, "DEPLOYMENT_REQUESTED", "DEPLOYMENT", d.id.toString(), now, "SUCCESS");
        return view(d);
    }

    @Transactional
    public DeploymentView transition(UUID organizationId, Set<UUID> environments, UUID deploymentId,
            String nextStatus, String failureReason, String actor) {
        DeploymentEntity d = DeploymentEntity.findById(deploymentId, LockModeType.PESSIMISTIC_WRITE);
        if (d == null || !organizationId.equals(d.organizationId) || environments == null || !environments.contains(d.environmentId))
            throw new NotFoundException("Deployment not found");
        String next = required(nextStatus, "status").toUpperCase();
        if (!allowed(d.status, next)) throw new jakarta.ws.rs.WebApplicationException(
                "Invalid deployment transition " + d.status + " -> " + next, 409);
        Instant now = Instant.now();
        d.status = next; d.updatedAt = now;
        if ("FAILED".equals(next)) d.failureReason = bounded(failureReason, 1000);
        if (terminal(next)) d.completedAt = now;
        audit.append(d.organizationId, d.environmentId, actor, "DEPLOYMENT_" + next,
                "DEPLOYMENT", d.id.toString(), now, "SUCCESS");
        return view(d);
    }

    @Transactional
    public DeploymentPage list(UUID organizationId, Set<UUID> environments, int page, int size) {
        if (environments == null || environments.isEmpty()) throw new NotFoundException("No environments in scope");
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be >= 0 and size 1..100");
        var query = DeploymentEntity.find("organizationId = ?1 and environmentId in ?2 order by createdAt desc",
                organizationId, environments);
        long total = query.count();
        List<DeploymentView> items = query.page(io.quarkus.panache.common.Page.of(page, size)).list().stream()
                .map(e -> view((DeploymentEntity)e)).toList();
        return new DeploymentPage(items, total, page, size);
    }

    private static boolean allowed(String current, String next) {
        return switch (current) {
            case "REQUESTED" -> next.equals("VALIDATING") || next.equals("CANCELLED") || next.equals("FAILED");
            case "VALIDATING" -> next.equals("READY") || next.equals("FAILED") || next.equals("CANCELLED");
            case "READY" -> next.equals("DEPLOYING") || next.equals("CANCELLED") || next.equals("FAILED");
            case "DEPLOYING" -> next.equals("FAILED") || next.equals("ROLLING_BACK");
            case "DEPLOYED" -> next.equals("ROLLING_BACK");
            case "ROLLING_BACK" -> next.equals("ROLLED_BACK") || next.equals("FAILED");
            default -> false;
        };
    }
    private static boolean terminal(String s) { return Set.of("DEPLOYED","FAILED","CANCELLED","ROLLED_BACK").contains(s); }
    private static DeploymentView view(DeploymentEntity d) {
        return new DeploymentView(d.id,d.deploymentId,d.runtimeId,d.workflowId,d.workflowVersion,d.artifactDigest,
                d.manifestDigest,d.status,d.createdAt,d.updatedAt,d.completedAt,d.failureReason);
    }
    private static String requiredBounded(String s,int max,String n){
        String value = required(s,n);
        if(value.length()>max) throw new IllegalArgumentException(n+" exceeds "+max+" characters");
        return value;
    }
    private static String required(String s,String n){if(s==null||s.isBlank())throw new IllegalArgumentException(n+" required");return s.trim();}
    private static void digest(String s,String n){if(s==null||!s.matches("(?i)sha256:[0-9a-f]{64}"))throw new IllegalArgumentException(n+" must be sha256:<64 hex chars>");}
    private static String bounded(String s,int max){if(s==null)return null;return s.length()>max?s.substring(0,max):s;}
}
