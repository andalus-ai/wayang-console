package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wc_deployment_reconciliation",
    uniqueConstraints = @UniqueConstraint(name = "uk_wc_reconciliation_deployment", columnNames = "deployment_id"),
    indexes = @Index(name = "idx_wc_reconciliation_state", columnList = "reconciliation_state, updated_at"))
public class DeploymentReconciliationEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "deployment_id", nullable = false, unique = true) public UUID deploymentId;
    @Column(name = "desired_state", nullable = false, length = 24) public String desiredState;
    @Column(nullable = false) public long generation;
    @Column(name = "observed_state", nullable = false, length = 24) public String observedState;
    @Column(name = "observed_artifact_digest", length = 71) public String observedArtifactDigest;
    @Column(name = "observed_command_id") public UUID observedCommandId;
    @Column(name = "report_sequence", nullable = false) public long reportSequence;
    @Column(name = "observed_at") public Instant observedAt;
    @Column(name = "received_at") public Instant receivedAt;
    @Column(name = "reconciliation_state", nullable = false, length = 32) public String reconciliationState;
    @Column(length = 1000) public String diagnostic;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
