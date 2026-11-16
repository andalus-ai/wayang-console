package ai.wayang.console.application;

/**
 * Resolves a provider-owned secret reference without exposing secret storage to Console APIs.
 *
 * <p>Implementations should delegate to a secret manager (for example Vault, a cloud secret
 * manager, or a deployment-mounted secret broker). The returned array is owned by the caller
 * and must be cleared after use. Implementations must never log secret values.</p>
 */
public interface ReconciliationSlaWebhookSecretResolver {
    char[] resolve(String secretReference);
}
