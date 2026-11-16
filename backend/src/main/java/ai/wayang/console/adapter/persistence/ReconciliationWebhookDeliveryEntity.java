package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_reconciliation_webhook_delivery",
        uniqueConstraints = @UniqueConstraint(name = "uk_wc_recon_webhook_event",
                columnNames = {"organization_id", "environment_id", "audit_sequence"}),
        indexes = {
                @Index(name = "idx_wc_recon_webhook_due", columnList = "delivery_status, next_attempt_at, lease_until"),
                @Index(name = "idx_wc_recon_webhook_scope_status", columnList = "organization_id, environment_id, delivery_status")
        })
public class ReconciliationWebhookDeliveryEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "audit_sequence", nullable = false) public long auditSequence;
    @Column(name = "record_hash", nullable = false, length = 64) public String recordHash;
    @Column(name = "deployment_id", nullable = false) public UUID deploymentId;
    @Column(name = "from_status", nullable = false, length = 16) public String fromStatus;
    @Column(name = "to_status", nullable = false, length = 16) public String toStatus;
    @Column(name = "occurred_at", nullable = false) public Instant occurredAt;
    @Column(name = "delivery_status", nullable = false, length = 16) public String status;
    @Column(name = "attempt_count", nullable = false) public int attemptCount;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "next_attempt_at") public Instant nextAttemptAt;
    @Column(name = "last_attempt_at") public Instant lastAttemptAt;
    @Column(name = "lease_until") public Instant leaseUntil;
    @Column(name = "delivered_at") public Instant deliveredAt;
    @Column(name = "last_failure_code", length = 80) public String lastFailureCode;
}
