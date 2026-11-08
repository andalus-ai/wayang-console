package ai.wayang.console.application;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactSignatureVerifierTest {
 @Test void verifiesCanonicalArtifactManifestAndKeyIdentity() throws Exception {
  var generator=KeyPairGenerator.getInstance("Ed25519");
  var pair=generator.generateKeyPair();
  String digest="sha256:"+"a".repeat(64), manifest="sha256:"+"b".repeat(64), keyId="release-2026";
  String canonical=digest+"\n"+manifest+"\n"+keyId;
  Signature signer=Signature.getInstance("Ed25519");signer.initSign(pair.getPrivate());
  signer.update(canonical.getBytes(StandardCharsets.UTF_8));
  String signature=Base64.getEncoder().encodeToString(signer.sign());
  String publicKey=Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
  assertTrue(ArtifactSignatureVerifier.verify(publicKey,digest,manifest,keyId,signature));
  assertFalse(ArtifactSignatureVerifier.verify(publicKey,digest,manifest,"other-key",signature));
 }
}
