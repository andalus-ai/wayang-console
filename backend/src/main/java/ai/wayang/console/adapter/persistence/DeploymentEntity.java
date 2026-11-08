package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_deployment", indexes = {
    @Index(name = "idx_wc_deployment_scope", columnList = "organization_id, environment_id, created_at"),
    @Index(name = "idx_wc_deployment_runtime", columnList = "runtime_id, created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_wc_deployment_scope_id",
        columnNames = {"organization_id", "environment_id", "deployment_id"}))
public class DeploymentEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "deployment_id", nullable = false, length = 160) public String deploymentId;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "runtime_id") public UUID runtimeId;
    @Column(name = "workflow_id", nullable = false, length = 180) public String workflowId;
    @Column(name = "workflow_version", nullable = false, length = 80) public String workflowVersion;
    @Column(name = "artifact_digest", nullable = false, length = 128) public String artifactDigest;
    @Column(name = "manifest_digest", nullable = false, length = 128) public String manifestDigest;
    @Column(name = "minimum_runtime_version", length = 80) public String minimumRuntimeVersion;
    @Column(name = "status", nullable = false, length = 24) public String status;
    @Column(name = "requested_by", nullable = false, length = 255) public String requestedBy;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    @Column(name = "completed_at") public Instant completedAt;
    @Column(name = "failure_reason", length = 1000) public String failureReason;
}
