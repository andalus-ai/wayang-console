package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.RuntimeInstanceEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;

@ApplicationScoped
public class RuntimeLeaseReconciler {
    /**
     * Lease expiry means the control plane has lost contact; it is not an assertion
     * that the runtime process or its workflows have stopped.
     */
    @Scheduled(every = "30s", delayed = "30s")
    @Transactional
    void markExpiredLeasesUnreachable() {
        RuntimeInstanceEntity.update(
                "status = ?1 where status = ?2 and leaseExpiresAt is not null and leaseExpiresAt <= ?3",
                "UNREACHABLE", "ONLINE", Instant.now());
    }
}
