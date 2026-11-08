package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_plugin_catalog_governance",
    uniqueConstraints = @UniqueConstraint(name = "uk_wc_plugin_governance_entry", columnNames = "catalog_entry_id"),
    indexes = @Index(name = "idx_wc_plugin_governance_scope_status",
        columnList = "organization_id, environment_id, lifecycle_status"))
public class PluginCatalogGovernanceEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "catalog_entry_id", nullable = false) public UUID catalogEntryId;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "lifecycle_status", nullable = false, length = 24) public String lifecycleStatus;
    @Column(name = "reason", length = 1000) public String reason;
    @Column(name = "changed_by", nullable = false, length = 255) public String changedBy;
    @Column(name = "changed_at", nullable = false) public Instant changedAt;
    @Version @Column(name = "row_version", nullable = false) public long rowVersion;
}
