package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_publisher_signing_key_event",
    indexes = @Index(name = "idx_wc_publisher_key_event_scope_time",
        columnList = "organization_id, environment_id, occurred_at"))
public class PublisherSigningKeyEventEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "key_id", nullable = false, length = 120) public String keyId;
    @Column(name = "fingerprint", nullable = false, length = 64) public String fingerprint;
    @Column(name = "event_type", nullable = false, length = 24) public String eventType;
    @Column(name = "actor_subject", nullable = false, length = 255) public String actorSubject;
    @Column(name = "reason", length = 1000) public String reason;
    @Column(name = "occurred_at", nullable = false) public Instant occurredAt;
}
