package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.PluginCatalogEntryEntity;
import ai.wayang.console.adapter.persistence.PluginCatalogGovernanceEntity;
import ai.wayang.console.adapter.persistence.PluginCatalogGovernanceEventEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ConflictException;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PluginCatalogGovernanceService {
    public enum Status { ACTIVE, DEPRECATED, REVOKED }
    public enum Action { DEPRECATE, REACTIVATE, REVOKE }

    @Inject EntityManager entityManager;
    @Inject AuditIntegrityService audit;

    public record TransitionCommand(Action action, String reason) {}
    public record GovernanceStatus(UUID catalogEntryId, String status, String reason,
            String changedBy, Instant changedAt, long revision) {}
    public record GovernanceEvent(UUID id, String fromStatus, String toStatus,
            String reason, String actor, Instant occurredAt) {}

    @Transactional
    public GovernanceStatus get(UUID organizationId, UUID environmentId, String pluginId, String version) {
        PluginCatalogEntryEntity entry = findEntry(organizationId, environmentId, pluginId, version);
        if (entry == null) throw new NotFoundException("Plugin catalog entry not found");
        PluginCatalogGovernanceEntity state = findState(entry.id);
        return state == null
                ? new GovernanceStatus(entry.id, Status.ACTIVE.name(), null, entry.publishedBy, entry.createdAt, 0)
                : view(state);
    }

    @Transactional
    public GovernanceStatus transition(UUID organizationId, UUID environmentId, String pluginId,
            String version, String actor, TransitionCommand command) {
        if (command == null || command.action() == null) throw new BadRequestException("Governance action is required");
        if (actor == null || actor.isBlank()) throw new BadRequestException("Actor is required");
        String reason = normalizeReason(command.reason());
        PluginCatalogEntryEntity entry = findEntry(organizationId, environmentId, pluginId, version);
        if (entry == null) throw new NotFoundException("Plugin catalog entry not found");
        entry = entityManager.find(PluginCatalogEntryEntity.class, entry.id, LockModeType.PESSIMISTIC_WRITE);

        PluginCatalogGovernanceEntity state = findState(entry.id);
        if (state == null) {
            state = new PluginCatalogGovernanceEntity();
            state.id = UUID.randomUUID();
            state.catalogEntryId = entry.id;
            state.organizationId = organizationId;
            state.environmentId = environmentId;
            state.lifecycleStatus = Status.ACTIVE.name();
            state.changedBy = entry.publishedBy;
            state.changedAt = entry.createdAt;
            state.persist();
            entityManager.flush();
        }
        state = entityManager.find(PluginCatalogGovernanceEntity.class, state.id, LockModeType.PESSIMISTIC_WRITE);
        Status from = Status.valueOf(state.lifecycleStatus);
        Status to = target(from, command.action());
        if (from == to) {
            if (java.util.Objects.equals(state.reason, reason)) return view(state);
            throw new ConflictException("Repeated lifecycle action must use the existing reason");
        }

        Instant now = Instant.now();
        PluginCatalogGovernanceEventEntity event = new PluginCatalogGovernanceEventEntity();
        event.id = UUID.randomUUID();
        event.catalogEntryId = entry.id;
        event.organizationId = organizationId;
        event.environmentId = environmentId;
        event.fromStatus = from.name();
        event.toStatus = to.name();
        event.reason = reason;
        event.actorSubject = actor;
        event.occurredAt = now;
        event.persist();

        state.lifecycleStatus = to.name();
        state.reason = reason;
        state.changedBy = actor;
        state.changedAt = now;
        audit.append(organizationId, environmentId, actor, "PLUGIN_CATALOG_" + to.name(),
                "PLUGIN_CATALOG_ENTRY", entry.id.toString(), now, "SUCCESS");
        return view(state);
    }

    @Transactional
    public List<GovernanceEvent> history(UUID organizationId, UUID environmentId,
            String pluginId, String version) {
        PluginCatalogEntryEntity entry = findEntry(organizationId, environmentId, pluginId, version);
        if (entry == null) throw new NotFoundException("Plugin catalog entry not found");
        return PluginCatalogGovernanceEventEntity.<PluginCatalogGovernanceEventEntity>list(
                "organizationId = ?1 and environmentId = ?2 and catalogEntryId = ?3 order by occurredAt, id",
                organizationId, environmentId, entry.id).stream()
                .map(e -> new GovernanceEvent(e.id, e.fromStatus, e.toStatus, e.reason, e.actorSubject, e.occurredAt))
                .toList();
    }

    static Status target(Status from, Action action) {
        return switch (action) {
            case DEPRECATE -> {
                if (from != Status.ACTIVE) throw new ConflictException("Only ACTIVE plugins can be deprecated");
                yield Status.DEPRECATED;
            }
            case REACTIVATE -> {
                if (from != Status.DEPRECATED) throw new ConflictException("Only DEPRECATED plugins can be reactivated");
                yield Status.ACTIVE;
            }
            case REVOKE -> {
                if (from == Status.REVOKED) throw new ConflictException("REVOKED is terminal");
                yield Status.REVOKED;
            }
        };
    }

    private PluginCatalogEntryEntity findEntry(UUID organizationId, UUID environmentId, String pluginId, String version) {
        return PluginCatalogEntryEntity.find(
                "organizationId = ?1 and environmentId = ?2 and pluginId = ?3 and pluginVersion = ?4",
                organizationId, environmentId, pluginId, version).firstResult();
    }

    private PluginCatalogGovernanceEntity findState(UUID entryId) {
        return PluginCatalogGovernanceEntity.find("catalogEntryId", entryId).firstResult();
    }

    private GovernanceStatus view(PluginCatalogGovernanceEntity state) {
        return new GovernanceStatus(state.catalogEntryId, state.lifecycleStatus, state.reason,
                state.changedBy, state.changedAt, state.rowVersion);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) throw new BadRequestException("A non-empty governance reason is required");
        String normalized = reason.trim();
        if (normalized.length() > 1000) throw new BadRequestException("Governance reason must not exceed 1000 characters");
        return normalized;
    }
}
