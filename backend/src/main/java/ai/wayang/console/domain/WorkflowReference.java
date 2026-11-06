package ai.wayang.console.domain;

import java.util.Objects;

public record WorkflowReference(String workflowId, String workflowVersion, String definitionDigest, String deploymentId) {
    public WorkflowReference {
        workflowId = required(workflowId, "workflowId");
        workflowVersion = required(workflowVersion, "workflowVersion");
        definitionDigest = required(definitionDigest, "definitionDigest");
        deploymentId = required(deploymentId, "deploymentId");
    }
    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
