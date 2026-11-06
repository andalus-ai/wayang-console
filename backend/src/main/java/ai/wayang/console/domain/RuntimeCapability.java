package ai.wayang.console.domain;

import java.util.Objects;

public record RuntimeCapability(String componentId, String version, String capability) {
    public RuntimeCapability {
        componentId = required(componentId, "componentId");
        version = required(version, "version");
        capability = required(capability, "capability");
    }
    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
