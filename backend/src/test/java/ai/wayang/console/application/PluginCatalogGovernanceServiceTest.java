package ai.wayang.console.application;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PluginCatalogGovernanceServiceTest {
    @Test
    void activeCanBeDeprecatedAndDeprecatedCanBeReactivated() {
        assertEquals(PluginCatalogGovernanceService.Status.DEPRECATED,
                PluginCatalogGovernanceService.target(PluginCatalogGovernanceService.Status.ACTIVE,
                        PluginCatalogGovernanceService.Action.DEPRECATE));
        assertEquals(PluginCatalogGovernanceService.Status.ACTIVE,
                PluginCatalogGovernanceService.target(PluginCatalogGovernanceService.Status.DEPRECATED,
                        PluginCatalogGovernanceService.Action.REACTIVATE));
    }

    @Test
    void activeAndDeprecatedCanBeRevoked() {
        assertEquals(PluginCatalogGovernanceService.Status.REVOKED,
                PluginCatalogGovernanceService.target(PluginCatalogGovernanceService.Status.ACTIVE,
                        PluginCatalogGovernanceService.Action.REVOKE));
        assertEquals(PluginCatalogGovernanceService.Status.REVOKED,
                PluginCatalogGovernanceService.target(PluginCatalogGovernanceService.Status.DEPRECATED,
                        PluginCatalogGovernanceService.Action.REVOKE));
    }

    @Test
    void invalidTransitionsAreRejected() {
        assertConflict(() -> PluginCatalogGovernanceService.target(
                PluginCatalogGovernanceService.Status.REVOKED, PluginCatalogGovernanceService.Action.REACTIVATE));
        assertConflict(() -> PluginCatalogGovernanceService.target(
                PluginCatalogGovernanceService.Status.REVOKED, PluginCatalogGovernanceService.Action.DEPRECATE));
        assertConflict(() -> PluginCatalogGovernanceService.target(
                PluginCatalogGovernanceService.Status.DEPRECATED, PluginCatalogGovernanceService.Action.DEPRECATE));
    }

    @Test
    void revokedIsTerminal() {
        assertConflict(() -> PluginCatalogGovernanceService.target(
                PluginCatalogGovernanceService.Status.REVOKED, PluginCatalogGovernanceService.Action.REVOKE));
    }
    private static void assertConflict(Runnable action) {
        WebApplicationException exception = assertThrows(WebApplicationException.class, action::run);
        assertEquals(409, exception.getResponse().getStatus());
    }
}
