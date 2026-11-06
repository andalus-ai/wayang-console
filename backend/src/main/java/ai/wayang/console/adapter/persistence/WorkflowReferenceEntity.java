package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "wc_workflow_reference", indexes = @Index(name = "idx_wc_workflow_runtime", columnList = "runtime_id"))
public class WorkflowReferenceEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "runtime_id", nullable = false) public UUID runtimeId;
    @Column(name = "workflow_id", nullable = false, length = 180) public String workflowId;
    @Column(name = "workflow_version", nullable = false, length = 80) public String workflowVersion;
    @Column(name = "definition_digest", nullable = false, length = 128) public String definitionDigest;
    @Column(name = "deployment_id", nullable = false, length = 160) public String deploymentId;
}
