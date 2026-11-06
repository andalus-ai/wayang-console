package ai.wayang.console.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Console-side projection; scope is assigned by authenticated enrollment, not payload claims. */
public record RuntimeInstance(
    UUID id, String instanceId, UUID organizationId, UUID environmentId,
    String runtimeVersion, String deploymentId, String hostname, String platform,
    RuntimeStatus status, Instant registeredAt, Instant lastSeenAt, Instant leaseExpiresAt) {

    public RuntimeInstance {
        Objects.requireNonNull(id, "id");
        instanceId = required(instanceId, "instanceId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(environmentId, "environmentId");
        runtimeVersion = required(runtimeVersion, "runtimeVersion");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(registeredAt, "registeredAt");
    }

    public boolean leaseExpiredAt(Instant now) {
        return leaseExpiresAt != null && !leaseExpiresAt.isAfter(now);
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
