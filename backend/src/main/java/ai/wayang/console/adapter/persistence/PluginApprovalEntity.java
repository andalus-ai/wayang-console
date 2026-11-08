package ai.wayang.console.adapter.persistence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant; import java.util.UUID;
@Entity @Table(name="wc_plugin_approval",uniqueConstraints=@UniqueConstraint(name="uk_wc_plugin_approval_entry",columnNames={"catalog_entry_id"}))
public class PluginApprovalEntity extends PanacheEntityBase {
 @Id public UUID id;
 @Column(name="catalog_entry_id",nullable=false) public UUID catalogEntryId;
 @Column(name="organization_id",nullable=false) public UUID organizationId;
 @Column(name="environment_id",nullable=false) public UUID environmentId;
 @Column(name="approval_status",nullable=false,length=16) public String approvalStatus;
 @Column(name="requested_by",nullable=false,length=255) public String requestedBy;
 @Column(name="requested_at",nullable=false) public Instant requestedAt;
 @Column(name="decided_by",length=255) public String decidedBy;
 @Column(name="decided_at") public Instant decidedAt;
 @Column(name="reason",length=1000) public String reason;
 @Version @Column(name="row_version",nullable=false) public long rowVersion;
}