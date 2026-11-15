package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.AuditRecordEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Reads committed alert-intent records from the existing tamper-evident audit chain. */
@ApplicationScoped
public class ReconciliationSlaAlertEventReader {
    private static final String ALERT_ACTION = "RECONCILIATION_ALERT_%";
    @Inject EntityManager entityManager;

    public record AlertScope(UUID organizationId, UUID environmentId) {}

    @Transactional
    public List<AlertScope> scopes() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT DISTINCT organization_id, environment_id
                FROM wc_audit_record
                WHERE action LIKE ?1 AND environment_id IS NOT NULL
                ORDER BY organization_id, environment_id
                """).setParameter(1, ALERT_ACTION).getResultList();
        List<AlertScope> scopes = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            scopes.add(new AlertScope((UUID) row[0], (UUID) row[1]));
        }
        return List.copyOf(scopes);
    }

    @Transactional
    public List<DeploymentReconciliationService.SlaAlertTransition> after(
            UUID organizationId, UUID environmentId, long sequence, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<AuditRecordEntity> records = AuditRecordEntity.<AuditRecordEntity>find(
                "organizationId = ?1 and environmentId = ?2 and resourceType = 'DEPLOYMENT' "
                        + "and action like ?3 and sequenceNo > ?4 order by sequenceNo asc",
                organizationId, environmentId, ALERT_ACTION, sequence)
                .range(0, boundedLimit - 1).list();
        return records.stream().map(record -> {
            String transition = record.action.substring("RECONCILIATION_ALERT_".length());
            String[] states = transition.split("_TO_", 2);
            if (states.length != 2) return null;
            return new DeploymentReconciliationService.SlaAlertTransition(
                    record.organizationId, record.environmentId, UUID.fromString(record.resourceId),
                    states[0], states[1], record.occurredAt, record.actorSubject,
                    record.sequenceNo == null ? 0L : record.sequenceNo, record.recordHash);
        }).filter(java.util.Objects::nonNull).toList();
    }
}
