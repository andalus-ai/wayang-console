package ai.wayang.console.security;

/**
 * Port for resolving the current verified Console operator.
 *
 * The default implementation uses the configured OIDC provider. An optional
 * Numou integration can implement this port in a separate adapter without
 * making the Console core depend on Numou identity or persistence modules.
 */
public interface ConsoleOperatorIdentityProvider {
    ConsoleOperatorPrincipal current();
}
