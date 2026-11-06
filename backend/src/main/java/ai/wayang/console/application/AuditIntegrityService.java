package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.AuditRecordEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Appends and verifies a per-organization/environment SHA-256 audit chain. */
@ApplicationScoped
public class AuditIntegrityService {
    private static final String HASH_VERSION = "v1";
    private static final String GENESIS = "0".repeat(64);
    @Inject EntityManager entityManager;

    public record VerificationResult(UUID organizationId, UUID environmentId, long verifiedRecords,
            boolean valid, Long firstInvalidSequence, String storedHead, String computedHead) {}

    @Transactional
    public AuditRecordEntity append(UUID organizationId, UUID environmentId, String actor,
            String action, String resourceType, String resourceId, Instant occurredAt, String outcome) {
        if (organizationId == null || environmentId == null) throw new IllegalArgumentException("Audit scope is required");
        entityManager.createNativeQuery("""
            INSERT INTO wc_audit_chain_head (organization_id, environment_id, last_sequence, last_hash)
            VALUES (?1, ?2, 0, ?3) ON CONFLICT (organization_id, environment_id) DO NOTHING
            """).setParameter(1, organizationId).setParameter(2, environmentId).setParameter(3, GENESIS).executeUpdate();
        Object[] head = (Object[]) entityManager.createNativeQuery("""
            SELECT last_sequence, last_hash FROM wc_audit_chain_head
            WHERE organization_id = ?1 AND environment_id = ?2 FOR UPDATE
            """).setParameter(1, organizationId).setParameter(2, environmentId).getSingleResult();
        long sequence = ((Number) head[0]).longValue() + 1;
        AuditRecordEntity r = new AuditRecordEntity();
        r.id = UUID.randomUUID(); r.organizationId = organizationId; r.environmentId = environmentId;
        r.actorSubject = required(actor, "actor"); r.action = required(action, "action");
        r.resourceType = required(resourceType, "resourceType"); r.resourceId = required(resourceId, "resourceId");
        r.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        r.outcome = required(outcome, "outcome"); r.sequenceNo = sequence;
        r.hashVersion = HASH_VERSION; r.previousHash = (String) head[1]; r.recordHash = hash(r);
        r.persist();
        entityManager.createNativeQuery("""
            UPDATE wc_audit_chain_head SET last_sequence = ?3, last_hash = ?4
            WHERE organization_id = ?1 AND environment_id = ?2
            """).setParameter(1, organizationId).setParameter(2, environmentId)
                .setParameter(3, sequence).setParameter(4, r.recordHash).executeUpdate();
        return r;
    }

    @Transactional
    public VerificationResult verify(UUID organizationId, UUID environmentId) {
        Object[] head = (Object[]) entityManager.createNativeQuery("""
            SELECT last_sequence, last_hash FROM wc_audit_chain_head
            WHERE organization_id = ?1 AND environment_id = ?2
            """).setParameter(1, organizationId).setParameter(2, environmentId)
                .getResultStream().findFirst().orElse(new Object[]{0L, GENESIS});
        List<AuditRecordEntity> records = AuditRecordEntity.list(
                "organizationId = ?1 and environmentId = ?2 order by sequenceNo", organizationId, environmentId);
        long sequence = 1; String previous = GENESIS;
        for (AuditRecordEntity r : records) {
            if (r.sequenceNo != sequence || !previous.equals(r.previousHash)
                    || !HASH_VERSION.equals(r.hashVersion) || !hash(r).equals(r.recordHash)) {
                return new VerificationResult(organizationId, environmentId, sequence - 1, false,
                        r.sequenceNo, (String) head[1], previous);
            }
            previous = r.recordHash; sequence++;
        }
        boolean valid = ((Number) head[0]).longValue() == records.size() && previous.equals(head[1]);
        return new VerificationResult(organizationId, environmentId, records.size(), valid,
                valid ? null : sequence, (String) head[1], previous);
    }

    private static String hash(AuditRecordEntity r) {
        String canonical = field(HASH_VERSION) + field(r.organizationId.toString()) + field(r.environmentId.toString())
                + field(Long.toString(r.sequenceNo)) + field(r.previousHash) + field(r.id.toString())
                + field(r.actorSubject) + field(r.action) + field(r.resourceType) + field(r.resourceId)
                + field(r.occurredAt.toString()) + field(r.outcome);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
    private static String field(String value) { return value.length() + ":" + value; }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}