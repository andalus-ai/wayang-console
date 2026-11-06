package ai.wayang.console.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;

/** Request-scoped facade over the verified operator principal. */
@RequestScoped
public class ConsoleOperatorContext {
    @Inject ConsoleOperatorIdentityProvider identityProvider;

    public java.util.UUID organizationId() {
        return principal().organizationId();
    }

    public java.util.Set<java.util.UUID> environmentIds() {
        return principal().environmentIds();
    }

    public String subject() {
        return principal().subject();
    }

    public void requireRead() {
        if (!principal().canReadRuntime()) {
            throw new ForbiddenException("Runtime read permission is required");
        }
    }

    public void requireAdmin() {
        if (!principal().canAdminRuntime()) {
            throw new ForbiddenException("Runtime administration permission is required");
        }
    }

    private ConsoleOperatorPrincipal principal() {
        return identityProvider.current();
    }
}
