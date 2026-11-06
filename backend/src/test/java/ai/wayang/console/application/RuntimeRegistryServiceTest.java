package ai.wayang.console.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeRegistryServiceTest {
    @Test
    void credentialDigestIsDeterministicAndOneWay() {
        String token = "high-entropy-enrollment-token";
        String first = RuntimeRegistryService.digest(token);
        String second = RuntimeRegistryService.digest(token);
        assertEquals(first, second);
        assertEquals(64, first.length());
        assertNotEquals(token, first);
    }

    @Test
    void credentialDigestRejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeRegistryService.digest(" "));
    }
}
