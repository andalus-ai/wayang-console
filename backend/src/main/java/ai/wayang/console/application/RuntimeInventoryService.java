package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RuntimeInventoryService {
    private static final Duration RUNTIME_LEASE = Duration.ofMinutes(2);
    private static final int MAX_ITEMS = 1000;
    public record CapabilityInput(String componentId, String version, String capability) {}
    public record WorkflowInput(String workflowId, String workflowVersion, String definitionDigest, String deploymentId) {}
    public record InventoryResult(String revision, int capabilityCount, int workflowCount, Instant reportedAt) {}

    @Transactional
    public InventoryResult replaceInventory(UUID runtimeId, List<CapabilityInput> capabilities,
                                            List<WorkflowInput> workflows) {
        RuntimeInstanceEntity runtime = RuntimeInstanceEntity.findById(runtimeId);
        if (runtime == null || "REVOKED".equals(runtime.status)) throw new NotFoundException("Runtime not found");

        List<CapabilityInput> safeCapabilities = capabilities == null ? List.of() : new java.util.ArrayList<>(capabilities);
        List<WorkflowInput> safeWorkflows = workflows == null ? List.of() : new java.util.ArrayList<>(workflows);
        if (safeCapabilities.size() > MAX_ITEMS || safeWorkflows.size() > MAX_ITEMS)
            throw new IllegalArgumentException("Inventory report exceeds the 1000-item limit per collection");

        Set<List<String>> capabilityKeys = new HashSet<>();
        for (CapabilityInput input : safeCapabilities) {
            require(input != null, "Capability entries must not be null");
            String component = bounded(input.componentId(), 180, "componentId");
            String version = bounded(input.version(), 80, "version");
            String capability = bounded(input.capability(), 180, "capability");
            require(capabilityKeys.add((component + "\\u0000" + version + "\\u0000" + capability).toLowerCase(Locale.ROOT)),
                    "Inventory contains duplicate capabilities");
        }
        Set<String> workflowKeys = new HashSet<>();
        for (WorkflowInput input : safeWorkflows) {
            require(input != null, "Workflow entries must not be null");
            String id = bounded(input.workflowId(), 180, "workflowId");
            bounded(input.workflowVersion(), 80, "workflowVersion");
            String digest = bounded(input.definitionDigest(), 128, "definitionDigest");
            bounded(input.deploymentId(), 160, "deploymentId");
            require(digest.matches("(?i)sha256:[0-9a-f]{64}"), "definitionDigest must be sha256:<64 hex chars>");
            require(workflowKeys.add(id.toLowerCase(Locale.ROOT)), "Inventory contains duplicate workflow ids");
        }

        RuntimeCapabilityEntity.delete("runtimeId", runtimeId);
        WorkflowReferenceEntity.delete("runtimeId", runtimeId);
        for (CapabilityInput input : safeCapabilities) {
            RuntimeCapabilityEntity entity = new RuntimeCapabilityEntity();
            entity.id = UUID.randomUUID();
            entity.runtimeId = runtimeId;
            entity.componentId = input.componentId().trim();
            entity.version = input.version().trim();
            entity.capability = input.capability().trim();
            entity.persist();
        }
        for (WorkflowInput input : safeWorkflows) {
            WorkflowReferenceEntity entity = new WorkflowReferenceEntity();
            entity.id = UUID.randomUUID();
            entity.runtimeId = runtimeId;
            entity.workflowId = input.workflowId().trim();
            entity.workflowVersion = input.workflowVersion().trim();
            entity.definitionDigest = input.definitionDigest().trim();
            entity.deploymentId = input.deploymentId().trim();
            entity.persist();
        }

        Instant now = Instant.now();
        String revision = UUID.randomUUID().toString();
        runtime.inventoryRevision = revision;
        runtime.inventoryReportedAt = now;
        runtime.lastSeenAt = now;
        runtime.leaseExpiresAt = now.plus(RUNTIME_LEASE);
        runtime.status = "ONLINE";

        AuditRecordEntity audit = new AuditRecordEntity();
        audit.id = UUID.randomUUID();
        audit.organizationId = runtime.organizationId;
        audit.environmentId = runtime.environmentId;
        audit.actorSubject = "runtime:" + runtime.instanceId;
        audit.action = "RUNTIME_INVENTORY_REPORTED";
        audit.resourceType = "RUNTIME";
        audit.resourceId = runtime.id.toString();
        audit.occurredAt = now;
        audit.outcome = "SUCCESS";
        audit.persist();

        return new InventoryResult(revision, safeCapabilities.size(), safeWorkflows.size(), now);
    }

    private static String bounded(String value, int max, String name) {
        require(value != null && !value.isBlank(), name + " is required");
        String normalized = value.trim();
        require(normalized.length() <= max, name + " exceeds " + max + " characters");
        return normalized;
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
