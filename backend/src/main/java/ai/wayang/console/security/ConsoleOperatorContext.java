package ai.wayang.console.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import java.util.UUID;

/** Request-scoped facade over the verified operator principal. */
@RequestScoped
public class ConsoleOperatorContext {
    @Inject ConsoleOperatorIdentityProvider identityProvider;
    public UUID organizationId() { return principal().organizationId(); }
    public java.util.Set<UUID> environmentIds() { return principal().environmentIds(); }
    public String subject() { return principal().subject(); }
    public void requireRead() {
        if (!principal().canReadRuntime()) throw new ForbiddenException("Runtime read permission is required");
    }
    public void requireAdmin() {
        if (!principal().canAdminRuntime()) throw new ForbiddenException("Runtime administration permission is required");
    }
    public void requirePluginCatalogRead() {
        if (!principal().canReadPluginCatalog()) throw new ForbiddenException("Plugin catalog read permission is required");
    }
    public void requirePluginCatalogAdmin() {
        if (!principal().canAdminPluginCatalog()) throw new ForbiddenException("Plugin catalog administration permission is required");
    }
    public void requirePublisherTrustRead() {
        if (!principal().canReadPublisherTrust()) throw new ForbiddenException("Publisher trust read permission is required");
    }
    public void requirePublisherTrustAdmin() {
        if (!principal().canAdminPublisherTrust()) throw new ForbiddenException("Publisher trust administration permission is required");
    }
    public void requirePluginApprovalRead() { if (!principal().canReadPluginApproval()) throw new ForbiddenException("Plugin approval read permission is required"); }
    public void requirePluginApprovalAdmin() { if (!principal().canAdminPluginApproval()) throw new ForbiddenException("Plugin approval administration permission is required"); }
    public UUID requireEnvironment(String value) {
        if (value == null || value.isBlank()) {
            if (principal().environmentIds().size() == 1) return principal().environmentIds().iterator().next();
            throw new ForbiddenException("environmentId must be selected when multiple environments are authorized");
        }
        try {
            UUID id = UUID.fromString(value);
            if (!principal().environmentIds().contains(id)) throw new ForbiddenException("Environment is outside the operator's authorized scope");
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new ForbiddenException("Invalid environment scope");
        }
    }
    private ConsoleOperatorPrincipal principal() { return identityProvider.current(); }
}
