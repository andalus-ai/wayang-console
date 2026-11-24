package ai.wayang.console.application;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationSlaWebhookProviderTest {
    @Test
    void retryBackoffIsExponentialAndBounded() {
        assertEquals(30, ReconciliationSlaWebhookSink.backoffSeconds(1));
        assertEquals(60, ReconciliationSlaWebhookSink.backoffSeconds(2));
        assertEquals(120, ReconciliationSlaWebhookSink.backoffSeconds(3));
        assertEquals(3600, ReconciliationSlaWebhookSink.backoffSeconds(10));
        assertEquals(3600, ReconciliationSlaWebhookSink.backoffSeconds(20));
    }

    @Test
    void classifiesOnlyTransientHttpResponsesAsRetryable() {
        assertTrue(new ReconciliationSlaWebhookTransport.Response(200).successful());
        assertTrue(new ReconciliationSlaWebhookTransport.Response(429).retryable());
        assertTrue(new ReconciliationSlaWebhookTransport.Response(503).retryable());
        assertFalse(new ReconciliationSlaWebhookTransport.Response(400).retryable());
        assertFalse(new ReconciliationSlaWebhookTransport.Response(600).retryable());
    }

    @Test
    void transportRejectsLoopbackBeforeOpeningConnection() {
        var transport = new RestrictedHttpsWebhookTransport();
        assertThrows(IllegalArgumentException.class, () -> transport.post(
                URI.create("https://127.0.0.1/hook"), java.util.Map.of(),
                new byte[0], Duration.ofSeconds(1)));
    }

    @Test
    void transportRejectsLocalhostBeforeOpeningConnection() {
        var transport = new RestrictedHttpsWebhookTransport();
        assertThrows(IllegalArgumentException.class, () -> transport.post(
                URI.create("https://localhost/hook"), java.util.Map.of(),
                new byte[0], Duration.ofSeconds(1)));
    }
    @Test
    void secretVersionIsAnOpaqueBoundedIdentifier() {
        assertEquals("key-2026-11", ReconciliationSlaWebhookSupport.validateSecretVersion("key-2026-11"));
        assertEquals("legacy", ReconciliationSlaWebhookSupport.validateSecretVersion("legacy"));
        assertThrows(IllegalArgumentException.class,
                () -> ReconciliationSlaWebhookSupport.validateSecretVersion("../secret"));
        assertThrows(IllegalArgumentException.class,
                () -> ReconciliationSlaWebhookSupport.validateSecretVersion("contains spaces"));
        assertThrows(IllegalArgumentException.class,
                () -> ReconciliationSlaWebhookSupport.validateSecretVersion("x".repeat(65)));
    }
}

