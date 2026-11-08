package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.DeploymentCapabilityRequirementEntity;
import ai.wayang.console.adapter.persistence.DeploymentEntity;
import ai.wayang.console.adapter.persistence.RuntimeCapabilityEntity;
import ai.wayang.console.adapter.persistence.RuntimeInstanceEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class RuntimeCompatibilityEvaluator {
    private static final Duration MAX_INVENTORY_AGE = Duration.ofMinutes(5);

    public record Requirement(String componentId, String capability, String minimumVersion) {}
    public record Diagnostic(String code, String message) {}
    public record Evaluation(boolean compatible, UUID deploymentId, UUID runtimeId,
            String inventoryRevision, Instant inventoryReportedAt, List<Diagnostic> diagnostics) {}

    @Transactional
    public Evaluation evaluate(UUID deploymentId, UUID organizationId, Set<UUID> environments) {
        DeploymentEntity deployment = DeploymentEntity.findById(deploymentId);
        if (deployment == null || !organizationId.equals(deployment.organizationId)
                || environments == null || !environments.contains(deployment.environmentId)) {
            throw new NotFoundException("Deployment not found");
        }
        return evaluate(deployment);
    }

    @Transactional
    public Evaluation evaluate(DeploymentEntity deployment) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        RuntimeInstanceEntity runtime = RuntimeInstanceEntity.findById(deployment.runtimeId, LockModeType.PESSIMISTIC_READ);
        if (runtime == null) {
            diagnostics.add(new Diagnostic("RUNTIME_MISSING", "The deployment target runtime no longer exists"));
            return result(deployment, null, diagnostics);
        }
        Instant now = Instant.now();
        if (!"ONLINE".equalsIgnoreCase(runtime.status) || runtime.leaseExpiresAt == null || !runtime.leaseExpiresAt.isAfter(now)) {
            diagnostics.add(new Diagnostic("RUNTIME_OFFLINE", "The target runtime is offline or its lease has expired"));
        }
        if (deployment.minimumRuntimeVersion != null && !atLeast(runtime.runtimeVersion, deployment.minimumRuntimeVersion)) {
            diagnostics.add(new Diagnostic("RUNTIME_VERSION_UNSUPPORTED",
                    "Runtime version " + runtime.runtimeVersion + " does not satisfy minimum " + deployment.minimumRuntimeVersion));
        }
        if (runtime.inventoryRevision == null || runtime.inventoryReportedAt == null) {
            diagnostics.add(new Diagnostic("INVENTORY_UNKNOWN", "The runtime has not reported a capability inventory"));
        } else if (runtime.inventoryReportedAt.isBefore(now.minus(MAX_INVENTORY_AGE))) {
            diagnostics.add(new Diagnostic("INVENTORY_STALE", "The runtime capability inventory is older than five minutes"));
        }

        List<DeploymentCapabilityRequirementEntity> requirements =
                DeploymentCapabilityRequirementEntity.list("deploymentId", deployment.id);
        List<RuntimeCapabilityEntity> capabilities = RuntimeCapabilityEntity.list("runtimeId", runtime.id);
        for (DeploymentCapabilityRequirementEntity requirement : requirements) {
            RuntimeCapabilityEntity match = capabilities.stream()
                    .filter(capability -> same(capability.componentId, requirement.componentId)
                            && same(capability.capability, requirement.capability))
                    .filter(capability -> requirement.minimumVersion == null
                            || atLeast(capability.version, requirement.minimumVersion))
                    .findFirst().orElse(null);
            if (match == null) {
                String version = requirement.minimumVersion == null ? "" : " (minimum version " + requirement.minimumVersion + ")";
                diagnostics.add(new Diagnostic("CAPABILITY_MISSING",
                        "Runtime lacks " + requirement.componentId + "/" + requirement.capability + version));
            }
        }
        return result(deployment, runtime, diagnostics);
    }

    private static Evaluation result(DeploymentEntity deployment, RuntimeInstanceEntity runtime, List<Diagnostic> diagnostics) {
        return new Evaluation(diagnostics.isEmpty(), deployment.id, runtime == null ? deployment.runtimeId : runtime.id,
                runtime == null ? null : runtime.inventoryRevision, runtime == null ? null : runtime.inventoryReportedAt,
                List.copyOf(diagnostics));
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean atLeast(String actual, String minimum) {
        int[] actualParts = numericVersion(actual);
        int[] minimumParts = numericVersion(minimum);
        if (actualParts == null || minimumParts == null) return false;
        for (int i = 0; i < Math.max(actualParts.length, minimumParts.length); i++) {
            int a = i < actualParts.length ? actualParts[i] : 0;
            int b = i < minimumParts.length ? minimumParts[i] : 0;
            if (a != b) return a > b;
        }
        return true;
    }

    private static int[] numericVersion(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) normalized = normalized.substring(1);
        if (!normalized.matches("[0-9]+(?:\\.[0-9]+)*")) return null;
        try {
            String[] tokens = normalized.split("\\.");
            int[] parts = new int[tokens.length];
            for (int i = 0; i < tokens.length; i++) parts[i] = Integer.parseInt(tokens[i]);
            return parts;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
