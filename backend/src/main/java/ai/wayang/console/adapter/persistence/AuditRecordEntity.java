package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_audit_record", indexes = {
    @Index(name = "idx_wc_audit_scope_time", columnList = "organization_id, occurred_at"),
    @Index(name = "idx_wc_audit_resource", columnList = "resource_type, resource_id")
}, uniqueConstraints = @UniqueConstraint(name = "uk_wc_audit_scope_sequence",
        columnNames = {"organization_id", "environment_id", "sequence_no"}))
public class AuditRecordEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "actor_subject", nullable = false, length = 255) public String actorSubject;
    @Column(name = "action", nullable = false, length = 100) public String action;
    @Column(name = "resource_type", nullable = false, length = 80) public String resourceType;
    @Column(name = "resource_id", nullable = false, length = 180) public String resourceId;
    @Column(name = "occurred_at", nullable = false) public Instant occurredAt;
    @Column(name = "outcome", nullable = false, length = 24) public String outcome;
    @Column(name = "sequence_no") public Long sequenceNo;
    @Column(name = "hash_version", nullable = false, length = 16) public String hashVersion;
    @Column(name = "previous_hash", nullable = false, length = 64) public String previousHash;
    @Column(name = "record_hash", nullable = false, length = 64) public String recordHash;
}
