package ai.wayang.console.application;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Resolves opaque secret references through MicroProfile Config.
 *
 * <p>Production deployments should supply these properties from an external secret source
 * (Kubernetes Secret, Vault ConfigSource, or cloud secret-manager ConfigSource), not from
 * source-controlled application.properties. The reference is a property name, never a secret.</p>
 */
@ApplicationScoped
public class MicroProfileConfigWebhookSecretResolver implements ReconciliationSlaWebhookSecretResolver {
    @Override
    public char[] resolve(String secretReference) {
        if (secretReference == null || !secretReference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}")) {
            throw new IllegalArgumentException("Invalid webhook secret reference");
        }
        if (secretReference.startsWith("env:")) {
            String variable = secretReference.substring(4);
            if (!variable.matches("[A-Z][A-Z0-9_]{0,126}")) {
                throw new IllegalArgumentException("Invalid webhook environment secret reference");
            }
            String value = System.getenv(variable);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Webhook secret reference is unavailable");
            }
            return value.toCharArray();
        }
        Config config = ConfigProvider.getConfig();
        return config.getOptionalValue(secretReference, String.class)
                .filter(value -> !value.isBlank())
                .map(String::toCharArray)
                .orElseThrow(() -> new IllegalStateException("Webhook secret reference is unavailable"));
    }
}
