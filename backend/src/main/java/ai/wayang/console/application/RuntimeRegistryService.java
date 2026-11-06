package ai.wayang.console.application;

import ai.wayang.console.adapter.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import jakarta.inject.Inject;

@ApplicationScoped
public class RuntimeRegistryService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration REGISTRATION_TTL = Duration.ofDays(30);
    private static final Duration LEASE_TTL = Duration.ofMinutes(2);
    private static final int HEARTBEAT_SECONDS = 30;
    @Inject AuditIntegrityService auditIntegrity;

    public record IssuedEnrollmentToken(UUID tokenId, String token, Instant expiresAt) {}

    @Transactional
    public IssuedEnrollmentToken issueEnrollmentToken(UUID organizationId, UUID environmentId, Duration ttl) {
        if (organizationId == null || environmentId == null) throw new IllegalArgumentException("Organization and environment are required");
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("Enrollment token TTL must be between zero and seven days");
        String raw = randomToken();
        Instant now = Instant.now();
        EnrollmentTokenEntity entity = new EnrollmentTokenEntity();
        entity.id = UUID.randomUUID();
        entity.tokenHash = digest(raw);
        entity.organizationId = organizationId;
        entity.environmentId = environmentId;
        entity.createdAt = now;
        entity.expiresAt = now.plus(ttl);
        entity.persist();
        return new IssuedEnrollmentToken(entity.id, raw, entity.expiresAt);
    }

    public record EnrollmentResult(String registrationId, String registrationToken, Instant registrationExpiresAt,
                                   Instant leaseExpiresAt, int heartbeatIntervalSeconds) {}
    public record HeartbeatResult(Instant leaseExpiresAt, int heartbeatIntervalSeconds) {}
    public record AuthenticatedRuntime(UUID id, UUID organizationId, UUID environmentId) {}

    @Transactional
    public EnrollmentResult enroll(String enrollmentToken, String instanceId, String runtimeVersion,
                                   String deploymentId, String hostname, String platform) {
        var token = EnrollmentTokenEntity.find("tokenHash", digest(enrollmentToken)).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (!(token instanceof EnrollmentTokenEntity enrollment)
                || enrollment.revokedAt != null || enrollment.consumedAt != null || !enrollment.expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Enrollment credential is invalid or expired");
        }
        Instant now = Instant.now();
        enrollment.consumedAt = now;
        String rawRegistrationToken = randomToken();
        RuntimeInstanceEntity runtime = new RuntimeInstanceEntity();
        runtime.id = UUID.randomUUID();
        runtime.instanceId = required(instanceId, "instanceId");
        runtime.organizationId = enrollment.organizationId;
        runtime.environmentId = enrollment.environmentId;
        runtime.runtimeVersion = required(runtimeVersion, "runtimeVersion");
        runtime.deploymentId = deploymentId;
        runtime.hostname = hostname;
        runtime.platform = platform;
        runtime.status = "ONLINE";
        runtime.healthStatus = "UNKNOWN";
        runtime.registrationTokenHash = digest(rawRegistrationToken);
        runtime.registrationExpiresAt = now.plus(REGISTRATION_TTL);
        runtime.registeredAt = now;
        runtime.lastSeenAt = now;
        runtime.leaseExpiresAt = now.plus(LEASE_TTL);
        runtime.persist();

        auditIntegrity.append(enrollment.organizationId, enrollment.environmentId,
                "runtime:" + runtime.instanceId, "RUNTIME_ENROLLED", "RUNTIME", runtime.id.toString(), now, "SUCCESS");

        return new EnrollmentResult(runtime.id.toString(), rawRegistrationToken, runtime.registrationExpiresAt,
                runtime.leaseExpiresAt, HEARTBEAT_SECONDS);
    }

    @Transactional
    public HeartbeatResult heartbeat(String bearerToken, String health) {
        RuntimeInstanceEntity runtime = authenticateEntity(bearerToken);
        Instant now = Instant.now();
        runtime.lastSeenAt = now;
        runtime.leaseExpiresAt = now.plus(LEASE_TTL);
        runtime.status = "ONLINE";
        runtime.healthStatus = normalizeHealth(health);
        return new HeartbeatResult(runtime.leaseExpiresAt, HEARTBEAT_SECONDS);
    }

    public record CredentialRotation(String runtimeId, String token, Instant expiresAt, long credentialVersion) {}

    @Transactional
    public CredentialRotation rotateCredential(UUID runtimeId, String actor) {
        RuntimeInstanceEntity found = RuntimeInstanceEntity.findById(runtimeId, LockModeType.PESSIMISTIC_WRITE);
        if (found == null || "REVOKED".equals(found.status)
                || !found.registrationExpiresAt.isAfter(Instant.now())) {
            throw new SecurityException("Runtime is not eligible for credential rotation");
        }
        Instant now = Instant.now();
        String raw = randomToken();
        found.registrationTokenHash = digest(raw);
        found.registrationExpiresAt = now.plus(REGISTRATION_TTL);
        found.credentialVersion = Math.max(1, found.credentialVersion) + 1;
        found.credentialRotatedAt = now;
        auditIntegrity.append(found.organizationId, found.environmentId,
                actor, "RUNTIME_CREDENTIAL_ROTATED", "RUNTIME", found.id.toString(), now, "SUCCESS");
        return new CredentialRotation(found.id.toString(), raw, found.registrationExpiresAt, found.credentialVersion);
    }

    @Transactional
    public AuthenticatedRuntime authenticate(String bearerToken) {
        RuntimeInstanceEntity runtime = authenticateEntity(bearerToken);
        return new AuthenticatedRuntime(runtime.id, runtime.organizationId, runtime.environmentId);
    }

    private RuntimeInstanceEntity authenticateEntity(String rawToken) {
        var found = RuntimeInstanceEntity.find("registrationTokenHash", digest(rawToken))
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (!(found instanceof RuntimeInstanceEntity runtime)
                || "REVOKED".equals(runtime.status)
                || !runtime.registrationExpiresAt.isAfter(Instant.now())) {
            throw new SecurityException("Runtime credential is invalid or expired");
        }
        return runtime;
    }

    public static String digest(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(required(raw, "credential").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String normalizeHealth(String health) {
        if (health == null) return "UNKNOWN";
        return switch (health.trim().toUpperCase()) {
            case "HEALTHY", "DEGRADED", "UNHEALTHY" -> health.trim().toUpperCase();
            default -> "UNKNOWN";
        };
    }
}
