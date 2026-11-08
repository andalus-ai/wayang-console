package ai.wayang.console.application;

import jakarta.ws.rs.ConflictException;
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
        assertThrows(ConflictException.class, () -> PluginCatalogGovernanceService.target(
                PluginCatalogGovernanceService.Status.REVOKED, PluginCatalogGovernanceService.Action.REACTIVATE));
        assertThrows(ConflictException.class, () -> PluginCatalogGovernanceService.target(
                PluginCatalogGovernanceService.Status.REVOKED, PluginCatalogGovernanceService.Action.DEPRECATE));
        assertThrows(ConflictException.class, () -> PluginCatalogGovernanceService.target(
                PluginCatalogGovernanceService.Status.DEPRECATED, PluginCatalogGovernanceService.Action.DEPRECATE));
    }

    @Test
    void revokedIsTerminal() {
        assertThrows(ConflictException.class, () -> PluginCatalogGovernanceService.target(
                PluginCatalogGovernanceService.Status.REVOKED, PluginCatalogGovernanceService.Action.REVOKE));
    }
}
