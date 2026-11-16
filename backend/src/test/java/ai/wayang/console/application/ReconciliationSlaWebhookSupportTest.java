package ai.wayang.console.application;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationSlaWebhookSupportTest {
    @Test
    void acceptsOnlyExactAllowlistedHttpsHost() {
        URI endpoint = URI.create("https://alerts.example.com/hooks/wayang");
        assertEquals(endpoint, ReconciliationSlaWebhookSupport.validateEndpoint(
                endpoint, Set.of("alerts.example.com")));
    }

    @Test
    void rejectsUntrustedEndpointShapes() {
        Set<String> allowlist = Set.of("alerts.example.com");
        assertThrows(IllegalArgumentException.class, () -> ReconciliationSlaWebhookSupport
                .validateEndpoint(URI.create("http://alerts.example.com/hook"), allowlist));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationSlaWebhookSupport
                .validateEndpoint(URI.create("https://alerts.example.com.evil.test/hook"), allowlist));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationSlaWebhookSupport
                .validateEndpoint(URI.create("https://user@alerts.example.com/hook"), allowlist));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationSlaWebhookSupport
                .validateEndpoint(URI.create("https://alerts.example.com/hook?token=secret"), allowlist));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationSlaWebhookSupport
                .validateEndpoint(URI.create("https://localhost/hook"), Set.of("localhost")));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationSlaWebhookSupport
                .validateEndpoint(URI.create("https://127.0.0.1/hook"), Set.of("127.0.0.1")));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationSlaWebhookSupport
                .validateEndpoint(URI.create("https://alerts.example.com:8443/hook"), allowlist));
    }

    @Test
    void signsExactPayloadAndTimestamp() {
        char[] secret = "01234567890123456789012345678901".toCharArray();
        byte[] body = "{\"type\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String first = ReconciliationSlaWebhookSupport.signature(secret, 1_800_000_000L, body);
        assertEquals(first, ReconciliationSlaWebhookSupport.signature(secret, 1_800_000_000L, body));
        assertNotEquals(first, ReconciliationSlaWebhookSupport.signature(secret, 1_800_000_001L, body));
        assertTrue(first.matches("v1=[0-9a-f]{64}"));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationSlaWebhookSupport
                .signature("short".toCharArray(), 1_800_000_000L, body));
    }

    @Test
    void idempotencyKeyUsesAuditIdentity() {
        var transition = new DeploymentReconciliationService.SlaAlertTransition(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "WARNING", "CRITICAL", Instant.parse("2026-11-16T00:00:00Z"),
                "console:test", 42L, "audit-hash");
        String key = ReconciliationSlaWebhookSupport.idempotencyKey(transition);
        assertEquals(key, ReconciliationSlaWebhookSupport.idempotencyKey(transition));
        assertTrue(key.matches("[0-9a-f]{64}"));
    }
}
