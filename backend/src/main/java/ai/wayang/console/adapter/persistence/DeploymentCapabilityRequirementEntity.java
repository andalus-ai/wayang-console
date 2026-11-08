package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "wc_deployment_capability_requirement",
    indexes = @Index(name = "idx_wc_deployment_requirement", columnList = "deployment_id"))
public class DeploymentCapabilityRequirementEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "deployment_id", nullable = false) public UUID deploymentId;
    @Column(name = "component_id", nullable = false, length = 180) public String componentId;
    @Column(name = "capability", nullable = false, length = 180) public String capability;
    @Column(name = "minimum_version", length = 80) public String minimumVersion;
}
