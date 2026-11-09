package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.PublisherSigningKeyEntity;
import ai.wayang.console.adapter.persistence.PublisherSigningKeyEventEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PublisherSigningKeyTrustService {
    @Inject EntityManager entityManager;
    @Inject AuditIntegrityService audit;

    public record RegisterCommand(String keyId, String publicKeyDerBase64) {}
    public record RevokeCommand(String reason) {}
    public record KeyView(String keyId, String fingerprint, String algorithm, String status,
            String createdBy, Instant createdAt, String revokedBy, Instant revokedAt, String revocationReason) {}
    public record KeyVerification(boolean managedTrustConfigured, String fingerprint) {}
    public record KeyEvent(String keyId, String fingerprint, String eventType,
            String actor, String reason, Instant occurredAt) {}

    @Transactional
    public KeyView register(UUID organizationId, UUID environmentId, String actor, RegisterCommand command) {
        if (organizationId == null || environmentId == null) throw new BadRequestException("Trust scope is required");
        if (actor == null || actor.isBlank()) throw new BadRequestException("Actor is required");
        if (command == null || command.keyId() == null || !command.keyId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw new BadRequestException("keyId must be a stable identifier");
        String encoded = command.publicKeyDerBase64() == null ? "" : command.publicKeyDerBase64().trim();
        byte[] der;
        try {
            der = Base64.getDecoder().decode(encoded);
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception invalid) {
            throw new BadRequestException("publicKeyDerBase64 must contain a valid Ed25519 SubjectPublicKeyInfo DER key");
        }
        if (encoded.length() > 512) throw new BadRequestException("Encoded public key exceeds 512 characters");
        String fingerprint = sha256(der);
        PublisherSigningKeyEntity existing = PublisherSigningKeyEntity.find(
                "organizationId = ?1 and environmentId = ?2 and keyId = ?3",
                organizationId, environmentId, command.keyId()).firstResult();
        if (existing != null) {
            if (!existing.fingerprint.equals(fingerprint))
                throw new ConflictException("keyId is already bound to a different public key; use a new keyId");
            if ("REVOKED".equals(existing.keyStatus))
                throw new ConflictException("A revoked key cannot be reactivated; register a new keyId");
            return view(existing);
        }
        if (PublisherSigningKeyEntity.count(
                "organizationId = ?1 and environmentId = ?2 and fingerprint = ?3",
                organizationId, environmentId, fingerprint) > 0)
            throw new ConflictException("This public key is already registered under another keyId");

        Instant now = Instant.now();
        PublisherSigningKeyEntity key = new PublisherSigningKeyEntity();
        key.id = UUID.randomUUID(); key.organizationId = organizationId; key.environmentId = environmentId;
        key.keyId = command.keyId(); key.fingerprint = fingerprint; key.publicKeyDerBase64 = encoded;
        key.keyStatus = "ACTIVE"; key.createdBy = actor; key.createdAt = now; key.persist();
        appendEvent(key, "REGISTERED", actor, null, now);
        audit.append(organizationId, environmentId, actor, "PUBLISHER_SIGNING_KEY_REGISTERED",
                "PUBLISHER_SIGNING_KEY", key.id.toString(), now, "SUCCESS");
        return view(key);
    }

    @Transactional
    public List<KeyView> list(UUID organizationId, UUID environmentId) {
        return PublisherSigningKeyEntity.<PublisherSigningKeyEntity>list(
                "organizationId = ?1 and environmentId = ?2 order by createdAt desc",
                organizationId, environmentId).stream().map(this::view).toList();
    }

    @Transactional
    public KeyView revoke(UUID organizationId, UUID environmentId, String keyId, String actor, RevokeCommand command) {
        String reason = command == null || command.reason() == null ? "" : command.reason().trim();
        if (reason.isEmpty() || reason.length() > 1000)
            throw new BadRequestException("A revocation reason between 1 and 1000 characters is required");
        PublisherSigningKeyEntity key = PublisherSigningKeyEntity.find(
                "organizationId = ?1 and environmentId = ?2 and keyId = ?3",
                organizationId, environmentId, keyId).firstResult();
        if (key == null) throw new NotFoundException("Publisher signing key not found");
        key = entityManager.find(PublisherSigningKeyEntity.class, key.id, LockModeType.PESSIMISTIC_WRITE);
        if ("REVOKED".equals(key.keyStatus)) {
            if (reason.equals(key.revocationReason)) return view(key);
            throw new ConflictException("Signing key is already revoked");
        }
        Instant now = Instant.now();
        key.keyStatus = "REVOKED"; key.revokedBy = actor; key.revokedAt = now; key.revocationReason = reason;
        appendEvent(key, "REVOKED", actor, reason, now);
        audit.append(organizationId, environmentId, actor, "PUBLISHER_SIGNING_KEY_REVOKED",
                "PUBLISHER_SIGNING_KEY", key.id.toString(), now, "SUCCESS");
        return view(key);
    }

    @Transactional
    public List<KeyEvent> history(UUID organizationId, UUID environmentId, String keyId) {
        boolean exists = PublisherSigningKeyEntity.count(
                "organizationId = ?1 and environmentId = ?2 and keyId = ?3",
                organizationId, environmentId, keyId) > 0;
        if (!exists) throw new NotFoundException("Publisher signing key not found");
        return PublisherSigningKeyEventEntity.<PublisherSigningKeyEventEntity>list(
                "organizationId = ?1 and environmentId = ?2 and keyId = ?3 order by occurredAt, id",
                organizationId, environmentId, keyId).stream()
                .map(event -> new KeyEvent(event.keyId, event.fingerprint, event.eventType,
                        event.actorSubject, event.reason, event.occurredAt)).toList();
    }

    /** Returns null only when no managed key has ever been configured for this scope. */
    @Transactional
    public KeyVerification verifyIfManaged(UUID organizationId, UUID environmentId,
            String keyId, String artifactDigest, String manifestDigest, String signatureBase64) {
        List<PublisherSigningKeyEntity> keys = PublisherSigningKeyEntity.list(
                "organizationId = ?1 and environmentId = ?2", organizationId, environmentId);
        if (keys.isEmpty()) return null;
        PublisherSigningKeyEntity key = keys.stream().filter(k -> k.keyId.equals(keyId)).findFirst()
                .orElseThrow(() -> new jakarta.ws.rs.WebApplicationException("Signing key is not trusted in this environment", 400));
        if (!"ACTIVE".equals(key.keyStatus))
            throw new jakarta.ws.rs.WebApplicationException("Signing key has been revoked", 400);
        try {
            var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKeyDerBase64)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update((artifactDigest + "\n" + manifestDigest + "\n" + keyId).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(signatureBase64)))
                throw new jakarta.ws.rs.WebApplicationException("Artifact signature verification failed", 400);
            return new KeyVerification(true, key.fingerprint);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            throw e;
        } catch (Exception invalid) {
            throw new jakarta.ws.rs.WebApplicationException("Invalid signature or registered trust key", 400);
        }
    }

    private void appendEvent(PublisherSigningKeyEntity key, String type, String actor, String reason, Instant at) {
        PublisherSigningKeyEventEntity event = new PublisherSigningKeyEventEntity();
        event.id = UUID.randomUUID(); event.organizationId = key.organizationId; event.environmentId = key.environmentId;
        event.keyId = key.keyId; event.fingerprint = key.fingerprint; event.eventType = type;
        event.actorSubject = actor; event.reason = reason; event.occurredAt = at; event.persist();
    }

    private KeyView view(PublisherSigningKeyEntity key) {
        return new KeyView(key.keyId, key.fingerprint, "Ed25519", key.keyStatus, key.createdBy,
                key.createdAt, key.revokedBy, key.revokedAt, key.revocationReason);
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}
