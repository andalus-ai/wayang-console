package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_plugin_catalog_entry",
    uniqueConstraints = @UniqueConstraint(name = "uk_wc_plugin_catalog_scope_version",
        columnNames = {"organization_id", "environment_id", "plugin_id", "plugin_version"}),
    indexes = @Index(name = "idx_wc_plugin_catalog_scope_plugin",
        columnList = "organization_id, environment_id, plugin_id, created_at"))
public class PluginCatalogEntryEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "plugin_id", nullable = false, length = 180) public String pluginId;
    @Column(name = "plugin_version", nullable = false, length = 80) public String pluginVersion;
    @Column(name = "manifest_digest", nullable = false, length = 71) public String manifestDigest;
    @Column(name = "metadata_json", nullable = false, columnDefinition = "TEXT") public String metadataJson;
    @Column(name = "metadata_digest", nullable = false, length = 71) public String metadataDigest;
    @Column(name = "published_by", nullable = false, length = 255) public String publishedBy;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
