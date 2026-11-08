package ai.wayang.console.security;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleOperatorPrincipalTest {
    @Test
    void adminRoleIncludesReadPermission() {
        var principal = new ConsoleOperatorPrincipal("operator-1", UUID.randomUUID(),
                Set.of(UUID.randomUUID()), Set.of("console:runtime:admin"));
        assertTrue(principal.canAdminRuntime());
        assertTrue(principal.canReadRuntime());
    }

    @Test
    void readRoleDoesNotGrantAdministration() {
        var principal = new ConsoleOperatorPrincipal("operator-1", UUID.randomUUID(),
                Set.of(UUID.randomUUID()), Set.of("console:runtime:read"));
        assertTrue(principal.canReadRuntime());
        assertFalse(principal.canAdminRuntime());
    }

    @Test
    void emptyEnvironmentScopeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ConsoleOperatorPrincipal(
                "operator-1", UUID.randomUUID(), Set.of(), Set.of("console:runtime:read")));
    }

    @Test
    void verifiedClaimsProduceScopedPrincipal() {
        UUID organization = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        var principal = OidcConsoleOperatorIdentityProvider.fromVerifiedClaims(
                "operator-1", organization.toString(), java.util.List.of(environment.toString()),
                Set.of("console:runtime:read"));
        assertEquals(organization, principal.organizationId());
        assertEquals(Set.of(environment), principal.environmentIds());
        assertTrue(principal.canReadRuntime());
    }

    @Test
    void malformedOrganizationClaimIsRejected() {
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () ->
                OidcConsoleOperatorIdentityProvider.fromVerifiedClaims(
                        "operator-1", "not-a-uuid", java.util.List.of(UUID.randomUUID().toString()), Set.of()));
    }

    @Test
    void missingEnvironmentScopeIsRejected() {
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () ->
                OidcConsoleOperatorIdentityProvider.fromVerifiedClaims(
                        "operator-1", UUID.randomUUID().toString(), java.util.List.of(), Set.of()));
    }

    @Test
    void blankSubjectIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ConsoleOperatorPrincipal(
                " ", UUID.randomUUID(), Set.of(UUID.randomUUID()), Set.of()));
    }
}

