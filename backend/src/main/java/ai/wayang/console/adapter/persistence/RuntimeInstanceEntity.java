package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_runtime_instance",
    uniqueConstraints = @UniqueConstraint(name = "uk_wc_runtime_instance", columnNames = {"organization_id", "instance_id"}),
    indexes = @Index(name = "idx_wc_runtime_scope", columnList = "organization_id, environment_id"))
public class RuntimeInstanceEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "instance_id", nullable = false, length = 160) public String instanceId;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "runtime_version", nullable = false, length = 80) public String runtimeVersion;
    @Column(name = "deployment_id", length = 160) public String deploymentId;
    @Column(length = 255) public String hostname;
    @Column(length = 120) public String platform;
    @Column(nullable = false, length = 24) public String status;
    @Column(name = "health_status", nullable = false, length = 24) public String healthStatus;
    @Column(name = "registration_token_hash", nullable = false, unique = true, length = 64) public String registrationTokenHash;
    @Column(name = "registration_expires_at", nullable = false) public Instant registrationExpiresAt;
    @Column(name = "credential_version", nullable = false) public long credentialVersion;
    @Column(name = "credential_rotated_at") public Instant credentialRotatedAt;
    @Column(name = "registered_at", nullable = false) public Instant registeredAt;
    @Column(name = "last_seen_at") public Instant lastSeenAt;
    @Column(name = "lease_expires_at") public Instant leaseExpiresAt;
    @Column(name = "inventory_revision", length = 36) public String inventoryRevision;
    @Column(name = "inventory_reported_at") public Instant inventoryReportedAt;
}
