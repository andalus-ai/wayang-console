package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_management_command", indexes = {
    @Index(name = "idx_wc_command_runtime_delivery", columnList = "runtime_id, status, lease_until, created_at"),
    @Index(name = "idx_wc_command_deployment", columnList = "deployment_id")
}, uniqueConstraints = @UniqueConstraint(name = "uk_wc_command_idempotency", columnNames = "idempotency_key"))
public class ManagementCommandEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "runtime_id", nullable = false) public UUID runtimeId;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "deployment_id", nullable = false) public UUID deploymentId;
    @Column(name = "idempotency_key", nullable = false, length = 220) public String idempotencyKey;
    @Column(name = "command_type", nullable = false, length = 64) public String commandType;
    @Column(name = "typed_payload", nullable = false, columnDefinition = "TEXT") public String typedPayload;
    @Column(name = "status", nullable = false, length = 24) public String status;
    @Column(name = "attempt_count", nullable = false) public int attemptCount;
    @Column(name = "max_attempts", nullable = false) public int maxAttempts;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "expires_at", nullable = false) public Instant expiresAt;
    @Column(name = "lease_until") public Instant leaseUntil;
    @Column(name = "last_delivered_at") public Instant lastDeliveredAt;
    @Column(name = "acknowledged_at") public Instant acknowledgedAt;
    @Column(name = "completed_at") public Instant completedAt;
    @Column(name = "result_message", length = 1000) public String resultMessage;
}
