package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_plugin_catalog_governance_event",
    indexes = @Index(name = "idx_wc_plugin_governance_event_entry_time",
        columnList = "catalog_entry_id, occurred_at"))
public class PluginCatalogGovernanceEventEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "catalog_entry_id", nullable = false) public UUID catalogEntryId;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "from_status", length = 24) public String fromStatus;
    @Column(name = "to_status", nullable = false, length = 24) public String toStatus;
    @Column(name = "reason", length = 1000) public String reason;
    @Column(name = "actor_subject", nullable = false, length = 255) public String actorSubject;
    @Column(name = "occurred_at", nullable = false) public Instant occurredAt;
}
