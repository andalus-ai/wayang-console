package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.ArtifactEntity;
import ai.wayang.console.adapter.persistence.PluginCatalogEntryEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class PluginCatalogService {
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,179}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_-]{0,79}");
    private static final Pattern DIGEST = Pattern.compile("(?i)sha256:[0-9a-f]{64}");
    private static final int MAX_METADATA_BYTES = 1_048_576;
    @Inject ObjectMapper mapper;
    @Inject AuditIntegrityService audit;

    public record PublishCommand(String pluginId, String pluginVersion, String manifestDigest, JsonNode metadata) {}
    public record CatalogEntry(UUID id, UUID environmentId, String pluginId, String pluginVersion,
            String manifestDigest, String metadataDigest, JsonNode metadata, boolean artifactRegistered,
            String publishedBy, Instant createdAt) {}

    @Transactional
    public CatalogEntry publish(UUID organizationId, UUID environmentId, String actor, PublishCommand command) {
        validate(command);
        String canonical = canonical(command.metadata());
        String metadataDigest = sha256(canonical);
        PluginCatalogEntryEntity existing = PluginCatalogEntryEntity.find(
                "organizationId = ?1 and environmentId = ?2 and pluginId = ?3 and pluginVersion = ?4",
                organizationId, environmentId, command.pluginId(), command.pluginVersion()).firstResult();
        if (existing != null) {
            if (!existing.metadataDigest.equals(metadataDigest)
                    || !existing.manifestDigest.equalsIgnoreCase(command.manifestDigest())) {
                throw new WebApplicationException("Plugin version is immutable; publish a new version for changed metadata", 409);
            }
            return view(existing);
        }

        Instant now = Instant.now();
        PluginCatalogEntryEntity entry = new PluginCatalogEntryEntity();
        entry.id = UUID.randomUUID();
        entry.organizationId = organizationId;
        entry.environmentId = environmentId;
        entry.pluginId = command.pluginId();
        entry.pluginVersion = command.pluginVersion();
        entry.manifestDigest = command.manifestDigest().toLowerCase();
        entry.metadataJson = canonical;
        entry.metadataDigest = metadataDigest;
        entry.publishedBy = actor;
        entry.createdAt = now;
        entry.persist();
        audit.append(organizationId, environmentId, actor, "PLUGIN_CATALOG_PUBLISHED",
                "PLUGIN_CATALOG_ENTRY", entry.id.toString(), now, "SUCCESS");
        return view(entry);
    }

    @Transactional
    public List<CatalogEntry> list(UUID organizationId, UUID environmentId) {
        return PluginCatalogEntryEntity.<PluginCatalogEntryEntity>list(
                "organizationId = ?1 and environmentId = ?2 order by pluginId, pluginVersion",
                organizationId, environmentId).stream().map(this::view).toList();
    }

    @Transactional
    public CatalogEntry get(UUID organizationId, UUID environmentId, String pluginId, String version) {
        PluginCatalogEntryEntity entry = find(organizationId, environmentId, pluginId, version);
        if (entry == null) throw new NotFoundException("Plugin catalog entry not found");
        return view(entry);
    }

    private PluginCatalogEntryEntity find(UUID organizationId, UUID environmentId, String pluginId, String version) {
        return PluginCatalogEntryEntity.find(
                "organizationId = ?1 and environmentId = ?2 and pluginId = ?3 and pluginVersion = ?4",
                organizationId, environmentId, pluginId, version).firstResult();
    }

    private CatalogEntry view(PluginCatalogEntryEntity entry) {
        try {
            boolean artifactRegistered = ArtifactEntity.count(
                    "organizationId = ?1 and environmentId = ?2 and manifestDigest = ?3",
                    entry.organizationId, entry.environmentId, entry.manifestDigest) > 0;
            return new CatalogEntry(entry.id, entry.environmentId, entry.pluginId, entry.pluginVersion,
                    entry.manifestDigest, entry.metadataDigest, mapper.readTree(entry.metadataJson),
                    artifactRegistered, entry.publishedBy, entry.createdAt);
        } catch (Exception e) {
            throw new IllegalStateException("Stored plugin catalog metadata is invalid", e);
        }
    }

    private void validate(PublishCommand command) {
        if (command == null || command.pluginId() == null || !ID.matcher(command.pluginId()).matches())
            throw new WebApplicationException("pluginId is required and must be a stable identifier", 400);
        if (command.pluginVersion() == null || !VERSION.matcher(command.pluginVersion()).matches())
            throw new WebApplicationException("pluginVersion is required", 400);
        if (command.manifestDigest() == null || !DIGEST.matcher(command.manifestDigest()).matches())
            throw new WebApplicationException("manifestDigest must be sha256:<64 hex>", 400);
        if (command.metadata() == null || !command.metadata().isObject())
            throw new WebApplicationException("metadata must be a JSON object", 400);
        if (!command.pluginId().equals(command.metadata().path("plugin_id").asText())
                || !command.pluginVersion().equals(command.metadata().path("plugin_version").asText())
                || !command.manifestDigest().equalsIgnoreCase(command.metadata().path("manifest_digest").asText()))
            throw new WebApplicationException("Metadata identity and manifest_digest must match the catalog envelope", 400);
        String json = canonical(command.metadata());
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES)
            throw new WebApplicationException("Plugin metadata exceeds 1 MiB", 413);
    }

    private String canonical(JsonNode metadata) {
        try { return mapper.writeValueAsString(metadata); }
        catch (Exception e) { throw new WebApplicationException("Metadata is not valid JSON", 400); }
    }

    private static String sha256(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}
