package ai.wayang.console.application;
import java.io.*;
import java.util.UUID;
/** Storage port; adapters must never expose storage credentials to runtimes. */
public interface ArtifactStorage {
 record StoredObject(String objectKey,String sha256,long sizeBytes) {}
 StoredObject put(UUID organizationId,UUID environmentId,String expectedDigest,InputStream content,long maxBytes) throws IOException;
 InputStream open(String objectKey) throws IOException;
 void delete(String objectKey) throws IOException;
}