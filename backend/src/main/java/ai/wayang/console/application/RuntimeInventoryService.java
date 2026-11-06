package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RuntimeInventoryService {
    public record CapabilityInput(String componentId, String version, String capability) {}
    public record WorkflowInput(String workflowId, String workflowVersion, String definitionDigest, String deploymentId) {}
    public record InventoryResult(String revision, int capabilityCount, int workflowCount) {}

    @Transactional
    public InventoryResult replaceInventory(UUID runtimeId, List<CapabilityInput> capabilities,
                                            List<WorkflowInput> workflows) {
        RuntimeInstanceEntity runtime = RuntimeInstanceEntity.findById(runtimeId);
        if (runtime == null) throw new IllegalArgumentException("Runtime not found");

        List<CapabilityInput> safeCapabilities = capabilities == null ? List.of() : new java.util.ArrayList<>(capabilities);
        List<WorkflowInput> safeWorkflows = workflows == null ? List.of() : new java.util.ArrayList<>(workflows);
        if (safeCapabilities.size() > 1000 || safeWorkflows.size() > 1000)
            throw new IllegalArgumentException("Inventory report exceeds the 1000-item limit per collection");

        for (CapabilityInput input : safeCapabilities)
            require(input != null && text(input.componentId()) && text(input.version()) && text(input.capability()),
                    "Each capability requires componentId, version, and capability");
        for (WorkflowInput input : safeWorkflows)
            require(input != null && text(input.workflowId()) && text(input.workflowVersion())
                    && text(input.definitionDigest()) && text(input.deploymentId()),
                    "Each workflow requires id, version, digest, and deploymentId");

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

        AuditRecordEntity audit = new AuditRecordEntity();
        audit.id = UUID.randomUUID();
        audit.organizationId = runtime.organizationId;
        audit.environmentId = runtime.environmentId;
        audit.actorSubject = "runtime:" + runtime.instanceId;
        audit.action = "RUNTIME_INVENTORY_REPORTED";
        audit.resourceType = "RUNTIME";
        audit.resourceId = runtime.id.toString();
        audit.occurredAt = Instant.now();
        audit.outcome = "SUCCESS";
        audit.persist();

        return new InventoryResult(UUID.randomUUID().toString(), safeCapabilities.size(), safeWorkflows.size());
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
