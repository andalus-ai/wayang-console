package ai.wayang.console.adapter.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "wc_runtime_capability", indexes = @Index(name = "idx_wc_capability_runtime", columnList = "runtime_id"))
public class RuntimeCapabilityEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "runtime_id", nullable = false) public UUID runtimeId;
    @Column(name = "component_id", nullable = false, length = 180) public String componentId;
    @Column(nullable = false, length = 80) public String version;
    @Column(nullable = false, length = 180) public String capability;
}
