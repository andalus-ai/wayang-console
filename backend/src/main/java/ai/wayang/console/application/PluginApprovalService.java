package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PluginApprovalService {
    @Inject EntityManager em;
    @Inject AuditIntegrityService audit;
    @Inject ArtifactRegistryService artifacts;

    public record Decision(String action, String reason) {}
    public record ApprovalView(UUID catalogEntryId, String status, String requestedBy, Instant requestedAt,
            String decidedBy, Instant decidedAt, String reason, long revision) {}
    public record ApprovalEvent(String fromStatus, String toStatus, String actor, String reason, Instant occurredAt) {}

    @Transactional
    public ApprovalView request(UUID org, UUID env, String plugin, String version, String actor) {
        PluginCatalogEntryEntity entry = find(org, env, plugin, version);
        if (entry == null) throw new NotFoundException("Catalog entry not found");
        if (actor == null || actor.isBlank()) throw new BadRequestException("Actor is required");

        // Serialize request creation with decisions for this immutable catalog version.
        // The unique constraint remains the final integrity guard, while this lock makes
        // concurrent duplicate requests resolve to the existing PENDING state rather
        // than racing into a constraint violation.
        entry = em.find(PluginCatalogEntryEntity.class, entry.id, LockModeType.PESSIMISTIC_WRITE);
        ensureDistributable(entry);
        PluginCatalogGovernanceEntity governance = governance(entry.id);
        if (governance != null && !"ACTIVE".equals(governance.lifecycleStatus))
            throw new WebApplicationException("Only ACTIVE catalog entries can be submitted for approval", 409);

        PluginApprovalEntity current = approval(entry.id);
        if (current != null) {
            if ("PENDING".equals(current.approvalStatus)) return view(current);
            throw new WebApplicationException("Approval is final for this catalog version; publish a new version to resubmit", 409);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PluginApprovalEntity state = new PluginApprovalEntity();
        state.id = UUID.randomUUID(); state.catalogEntryId = entry.id; state.organizationId = org;
        state.environmentId = env; state.approvalStatus = "PENDING"; state.requestedBy = actor;
        state.requestedAt = now; state.persist();
        event(entry, null, "PENDING", actor, "Submitted for distribution approval", now);
        audit.append(org, env, actor, "PLUGIN_APPROVAL_REQUESTED", "PLUGIN_CATALOG_ENTRY", entry.id.toString(), now, "SUCCESS");
        return view(state);
    }

    @Transactional
    public ApprovalView decide(UUID org, UUID env, String plugin, String version, String actor, Decision decision) {
        if (decision == null || decision.action() == null) throw new BadRequestException("Decision action required");
        if (actor == null || actor.isBlank()) throw new BadRequestException("Actor is required");
        String reason = decision.reason() == null ? "" : decision.reason().trim();
        if (reason.isEmpty() || reason.length() > 1000)
            throw new BadRequestException("Decision reason is required (max 1000)");
        String action = decision.action().trim().toUpperCase(java.util.Locale.ROOT);
        if (!action.equals("APPROVE") && !action.equals("REJECT"))
            throw new BadRequestException("action must be APPROVE or REJECT");

        PluginCatalogEntryEntity entry = find(org, env, plugin, version);
        if (entry == null) throw new NotFoundException("Catalog entry not found");
        entry = em.find(PluginCatalogEntryEntity.class, entry.id, LockModeType.PESSIMISTIC_WRITE);
        PluginApprovalEntity state = approval(entry.id);
        if (state == null) throw new WebApplicationException("Approval has not been requested", 409);
        state = em.find(PluginApprovalEntity.class, state.id, LockModeType.PESSIMISTIC_WRITE);
        if (!"PENDING".equals(state.approvalStatus)) throw new WebApplicationException("Approval decision is final", 409);
        if (actor.equals(state.requestedBy) || actor.equals(entry.publishedBy))
            throw new ForbiddenException("Publisher/requester cannot decide their own submission");

        if (action.equals("APPROVE")) {
            PluginCatalogGovernanceEntity gov = governance(entry.id);
            if (gov != null && !"ACTIVE".equals(gov.lifecycleStatus))
                throw new WebApplicationException("Only ACTIVE catalog entries may be approved", 409);
            ensureDistributable(entry);
        }
        Instant now = Instant.now();
        String status = action.equals("APPROVE") ? "APPROVED" : "REJECTED";
        state.approvalStatus = status; state.decidedBy = actor; state.decidedAt = now; state.reason = reason;
        event(entry, "PENDING", status, actor, reason, now);
        audit.append(org, env, actor, "PLUGIN_" + status, "PLUGIN_CATALOG_ENTRY", entry.id.toString(), now, "SUCCESS");
        return view(state);
    }

    @Transactional
    public ApprovalView get(UUID org, UUID env, String plugin, String version) {
        PluginCatalogEntryEntity entry = find(org, env, plugin, version);
        if (entry == null) throw new NotFoundException("Catalog entry not found");
        PluginApprovalEntity state = approval(entry.id);
        if (state == null) throw new NotFoundException("Approval not requested");
        return view(state);
    }

    @Transactional
    public List<ApprovalEvent> history(UUID org, UUID env, String plugin, String version) {
        PluginCatalogEntryEntity entry = find(org, env, plugin, version);
        if (entry == null) throw new NotFoundException("Catalog entry not found");
        return PluginApprovalEventEntity.<PluginApprovalEventEntity>list(
                "organizationId = ?1 and environmentId = ?2 and catalogEntryId = ?3 order by occurredAt, id",
                org, env, entry.id).stream()
                .map(e -> new ApprovalEvent(e.fromStatus, e.toStatus, e.actorSubject, e.reason, e.occurredAt)).toList();
    }

    private void ensureDistributable(PluginCatalogEntryEntity entry) {
        artifacts.assertManifestDistributable(entry.organizationId, entry.environmentId, entry.manifestDigest);
    }
    private PluginCatalogEntryEntity find(UUID org, UUID env, String plugin, String version) {
        return PluginCatalogEntryEntity.find(
                "organizationId = ?1 and environmentId = ?2 and pluginId = ?3 and pluginVersion = ?4",
                org, env, plugin, version).firstResult();
    }
    private PluginApprovalEntity approval(UUID entryId) {
        return PluginApprovalEntity.find("catalogEntryId", entryId).firstResult();
    }
    private PluginCatalogGovernanceEntity governance(UUID entryId) {
        return PluginCatalogGovernanceEntity.find("catalogEntryId", entryId).firstResult();
    }
    private void event(PluginCatalogEntryEntity entry, String from, String to, String actor, String reason, Instant at) {
        PluginApprovalEventEntity event = new PluginApprovalEventEntity();
        event.id = UUID.randomUUID(); event.catalogEntryId = entry.id; event.organizationId = entry.organizationId;
        event.environmentId = entry.environmentId; event.fromStatus = from; event.toStatus = to;
        event.actorSubject = actor; event.reason = reason; event.occurredAt = at; event.persist();
    }
    private ApprovalView view(PluginApprovalEntity a) {
        return new ApprovalView(a.catalogEntryId, a.approvalStatus, a.requestedBy, a.requestedAt,
                a.decidedBy, a.decidedAt, a.reason, a.rowVersion);
    }
}
