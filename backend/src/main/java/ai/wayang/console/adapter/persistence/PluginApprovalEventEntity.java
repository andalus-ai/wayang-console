package ai.wayang.console.adapter.persistence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="wc_plugin_approval_event",indexes=@Index(name="idx_wc_plugin_approval_event_scope",columnList="organization_id, environment_id, occurred_at"))
public class PluginApprovalEventEntity extends PanacheEntityBase {
 @Id public UUID id; @Column(name="catalog_entry_id",nullable=false) public UUID catalogEntryId;
 @Column(name="organization_id",nullable=false) public UUID organizationId; @Column(name="environment_id",nullable=false) public UUID environmentId;
 @Column(name="from_status",length=16) public String fromStatus; @Column(name="to_status",nullable=false,length=16) public String toStatus;
 @Column(name="actor_subject",nullable=false,length=255) public String actorSubject; @Column(name="reason",nullable=false,length=1000) public String reason;
 @Column(name="occurred_at",nullable=false) public Instant occurredAt;
}