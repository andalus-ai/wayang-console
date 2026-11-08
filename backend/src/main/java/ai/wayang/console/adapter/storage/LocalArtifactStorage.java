package ai.wayang.console.adapter.storage;

import ai.wayang.console.application.ArtifactStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

@ApplicationScoped
public class LocalArtifactStorage implements ArtifactStorage {
    @Inject @ConfigProperty(name="wayang.console.artifacts.root", defaultValue="./data/artifacts")
    public String configuredRoot;

    @Override
    public StoredObject put(UUID org, UUID env, String expected, InputStream body, long max) throws IOException {
        Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        Path dir = root.resolve(org.toString()).resolve(env.toString()).normalize();
        if (!dir.startsWith(root)) throw new IOException("Invalid artifact scope");
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".upload-", ".part");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long size;
            try (DigestInputStream in = new DigestInputStream(new BoundedInputStream(body, max), md)) {
                size = Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (size < 1 || size > max) throw new IOException("Artifact size is outside permitted range");
            String digest = "sha256:" + HexFormat.of().formatHex(md.digest());
            if (!digest.equalsIgnoreCase(expected)) throw new IOException("Artifact digest mismatch");
            String key = org + "/" + env + "/" + digest.substring(7);
            Path target = dir.resolve(digest.substring(7)).normalize();
            if (!target.startsWith(dir)) throw new IOException("Invalid artifact key");
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredObject(key, digest, size);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override public InputStream open(String key) throws IOException { return Files.newInputStream(resolve(key)); }
    @Override public void delete(String key) throws IOException { Files.deleteIfExists(resolve(key)); }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long max;
        private long count;
        BoundedInputStream(InputStream in, long max) { super(in); this.max = max; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > max) throw new IOException("Artifact exceeds configured size limit");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, Math.max(1, max - count + 1));
            int n = super.read(bytes, offset, allowed);
            if (n > 0) {
                count += n;
                if (count > max) throw new IOException("Artifact exceeds configured size limit");
            }
            return n;
        }
    }

    private Path resolve(String key) throws IOException {
        if (key == null || !key.matches("[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}/[0-9a-f]{64}"))
            throw new IOException("Invalid artifact object key");
        Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IOException("Invalid artifact object key");
        return path;
    }
}
