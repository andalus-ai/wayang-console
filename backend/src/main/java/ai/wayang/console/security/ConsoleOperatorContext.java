package ai.wayang.console.security;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import java.util.*;
import java.util.stream.Collectors;

@RequestScoped
public class ConsoleOperatorContext {
    @Inject SecurityIdentity identity;
    @Inject JsonWebToken jwt;

    public UUID organizationId() {
        requireAuthenticated();
        try { return UUID.fromString(jwt.getClaim("organization_id")); }
        catch (RuntimeException e) { throw new ForbiddenException("Operator token has no valid organization_id scope"); }
    }

    public Set<UUID> environmentIds() {
        requireAuthenticated();
        Object claim = jwt.getClaim("environment_ids");
        if (!(claim instanceof Collection<?> values) || values.isEmpty())
            throw new ForbiddenException("Operator token has no environment_ids scope");
        try {
            return values.stream().map(String::valueOf).map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException e) {
            throw new ForbiddenException("Operator token contains invalid environment_ids");
        }
    }

    public String subject() {
        requireAuthenticated();
        return jwt.getSubject();
    }

    public void requireRead() {
        requireAuthenticated();
        if (!identity.hasRole("console:runtime:read") && !identity.hasRole("console:runtime:admin"))
            throw new ForbiddenException("Runtime read permission is required");
    }

    public void requireAdmin() {
        requireAuthenticated();
        if (!identity.hasRole("console:runtime:admin"))
            throw new ForbiddenException("Runtime administration permission is required");
    }

    private void requireAuthenticated() {
        if (identity == null || identity.isAnonymous() || jwt == null || jwt.getSubject() == null)
            throw new NotAuthorizedException("Bearer access token required");
    }
}
