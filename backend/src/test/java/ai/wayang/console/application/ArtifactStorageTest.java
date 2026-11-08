package ai.wayang.console.application;

import ai.wayang.console.adapter.storage.LocalArtifactStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactStorageTest {
    @TempDir
    Path temp;

    @Test
    void verifiesDigestAndReadsBackStoredBytes() throws Exception {
        var storage = new LocalArtifactStorage();
        storage.configuredRoot = temp.toString();
        byte[] content = "wayang-artifact".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        UUID organizationId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();

        var stored = storage.put(organizationId, environmentId, digest,
                new ByteArrayInputStream(content), 1024);

        assertEquals(digest, stored.sha256());
        try (var downloaded = storage.open(stored.objectKey())) {
            assertArrayEquals(content, downloaded.readAllBytes());
        }
        assertThrows(IOException.class, () -> storage.put(organizationId, environmentId,
                "sha256:" + "0".repeat(64), new ByteArrayInputStream(content), 1024));
        storage.delete(stored.objectKey());
        assertThrows(IOException.class, () -> storage.open(stored.objectKey()));
    }

    @Test
    void enforcesMaximumSizeWhileStreaming() {
        var storage = new LocalArtifactStorage();
        storage.configuredRoot = temp.toString();
        assertThrows(IOException.class, () -> storage.put(UUID.randomUUID(), UUID.randomUUID(),
                "sha256:" + "0".repeat(64), new ByteArrayInputStream(new byte[16]), 8));
    }

    @Test
    void rejectsTraversalKeys() {
        var storage = new LocalArtifactStorage();
        storage.configuredRoot = temp.toString();
        assertThrows(IOException.class, () -> storage.open("../../etc/passwd"));
    }
}
