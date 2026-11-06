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
        return fromVerifiedClaims(jwt.getSubject(), jwt.getClaim("organization_id"),
                jwt.getClaim("environment_ids"), identity.getRoles());
    }

    static ConsoleOperatorPrincipal fromVerifiedClaims(String subject, Object organizationClaim,
                                                        Object environmentClaim, Set<String> roles) {
        if (subject == null || subject.isBlank()) {
            throw new NotAuthorizedException("Bearer access token required");
        }
        UUID organizationId;
        if (organizationClaim == null) {
            throw new ForbiddenException("Operator token is missing required organization_id scope");
        }
        try {
            organizationId = UUID.fromString(organizationClaim.toString());
        } catch (IllegalArgumentException invalid) {
            throw new ForbiddenException("Operator token contains invalid organization_id scope");
        }

        if (!(environmentClaim instanceof Collection<?> values) || values.isEmpty()) {
            throw new ForbiddenException("Operator token is missing required environment_ids scope");
        }
        final Set<UUID> environments;
        try {
            environments = values.stream().map(Object::toString).map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException invalid) {
            throw new ForbiddenException("Operator token contains invalid environment_ids scope");
        }
        return new ConsoleOperatorPrincipal(subject, organizationId, environments, roles);
    }
}
