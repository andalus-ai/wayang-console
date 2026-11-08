package ai.wayang.console.application;
import ai.wayang.console.adapter.storage.LocalArtifactStorage;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.Path;import java.security.MessageDigest;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ArtifactStorageTest{
 @TempDir Path temp;
 @Test void verifiesDigestAndReadsBackStoredBytes()throws Exception{
  var s=new LocalArtifactStorage();s.configuredRoot=temp.toString();byte[] b="wayang-artifact".getBytes(StandardCharsets.UTF_8);
  String d="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));UUID org=UUID.randomUUID(),env=UUID.randomUUID();
  var stored=s.put(org,env,d,new ByteArrayInputStream(b),1024);assertEquals(d,stored.sha256());assertArrayEquals(b,s.open(stored.objectKey()).readAllBytes());
  assertThrows(IOException.class,()->s.put(org,env,"sha256:"+"0".repeat(64),new ByteArrayInputStream(b),1024));
  s.delete(stored.objectKey());assertThrows(IOException.class,()->s.open(stored.objectKey()));
 }
 @Test void rejectsTraversalKeys(){var s=new LocalArtifactStorage();s.configuredRoot=temp.toString();assertThrows(IOException.class,()->s.open("../../etc/passwd"));}
}