package ai.wayang.console.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Maps a signature-verified OIDC access token to the Wayang Console security model. */
@RequestScoped
public class OidcConsoleOperatorIdentityProvider implements ConsoleOperatorIdentityProvider {
    private final SecurityIdentity identity;
    private final JsonWebToken jwt;

    @Inject
    public OidcConsoleOperatorIdentityProvider(SecurityIdentity identity, JsonWebToken jwt) {
        this.identity = identity;
        this.jwt = jwt;
    }

    @Override
    public ConsoleOperatorPrincipal current() {
        if (identity == null || identity.isAnonymous() || jwt == null
                || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new NotAuthorizedException("Bearer access token required");
        }

        UUID organizationId = uuidClaim("organization_id");
        Set<UUID> environments = environmentClaim();
        Set<String> roles = identity.getRoles();
        return new ConsoleOperatorPrincipal(jwt.getSubject(), organizationId, environments, roles);
    }

    private UUID uuidClaim(String name) {
        Object value = jwt.getClaim(name);
        if (value == null) {
            throw new ForbiddenException("Operator token is missing required " + name + " scope");
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException invalid) {
            throw new ForbiddenException("Operator token contains invalid " + name + " scope");
        }
    }

    private Set<UUID> environmentClaim() {
        Object value = jwt.getClaim("environment_ids");
        if (!(value instanceof Collection<?> values) || values.isEmpty()) {
            throw new ForbiddenException("Operator token is missing required environment_ids scope");
        }
        try {
            return values.stream().map(Object::toString).map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException invalid) {
            throw new ForbiddenException("Operator token contains invalid environment_ids scope");
        }
    }
}
