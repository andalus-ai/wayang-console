package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_publisher_signing_key",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_wc_publisher_key_scope_id",
            columnNames = {"organization_id", "environment_id", "key_id"}),
        @UniqueConstraint(name = "uk_wc_publisher_key_scope_fingerprint",
            columnNames = {"organization_id", "environment_id", "fingerprint"})
    },
    indexes = @Index(name = "idx_wc_publisher_key_scope_status",
        columnList = "organization_id, environment_id, key_status"))
public class PublisherSigningKeyEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "key_id", nullable = false, length = 120) public String keyId;
    @Column(name = "fingerprint", nullable = false, length = 64) public String fingerprint;
    @Column(name = "public_key_der_base64", nullable = false, length = 512) public String publicKeyDerBase64;
    @Column(name = "key_status", nullable = false, length = 16) public String keyStatus;
    @Column(name = "created_by", nullable = false, length = 255) public String createdBy;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "revoked_by", length = 255) public String revokedBy;
    @Column(name = "revoked_at") public Instant revokedAt;
    @Column(name = "revocation_reason", length = 1000) public String revocationReason;
    @Version @Column(name = "row_version", nullable = false) public long rowVersion;
}
