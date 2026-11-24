package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_reconciliation_webhook_cursor",
        uniqueConstraints = @UniqueConstraint(name = "uk_wc_recon_webhook_cursor_scope",
                columnNames = {"organization_id", "environment_id"}))
public class ReconciliationWebhookCursorEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(name = "environment_id", nullable = false) public UUID environmentId;
    @Column(name = "last_accepted_sequence", nullable = false) public long lastAcceptedSequence;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
