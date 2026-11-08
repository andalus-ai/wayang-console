package ai.wayang.console.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ConsoleOperatorPrincipal(String subject, UUID organizationId, Set<UUID> environmentIds, Set<String> roles) {
    public ConsoleOperatorPrincipal {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(environmentIds, "environmentIds must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        if (environmentIds.stream().anyMatch(Objects::isNull) || roles.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("scope and role values must not contain null");
        environmentIds = Set.copyOf(environmentIds);
        roles = Set.copyOf(roles);
        if (environmentIds.isEmpty()) throw new IllegalArgumentException("at least one valid environment scope is required");
    }
    public boolean canReadRuntime() { return roles.contains("console:runtime:read") || canAdminRuntime(); }
    public boolean canAdminRuntime() { return roles.contains("console:runtime:admin"); }
}
