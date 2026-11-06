package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_enrollment_token", indexes = @Index(name = "idx_wc_enrollment_hash", columnList = "token_hash", unique = true))
public class EnrollmentTokenEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) public String tokenHash;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "expires_at", nullable = false) public Instant expiresAt;
    @Column(name = "consumed_at") public Instant consumedAt;
    @Column(name = "revoked_at") public Instant revokedAt;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
