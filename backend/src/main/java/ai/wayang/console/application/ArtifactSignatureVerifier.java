package ai.wayang.console.application;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Verifies release signatures over the canonical artifact/manifest identity tuple. */
public final class ArtifactSignatureVerifier {
    private ArtifactSignatureVerifier() {}

    public static boolean verify(String publicKeyDerBase64, String artifactDigest,
            String manifestDigest, String keyId, String signatureBase64) throws Exception {
        if (publicKeyDerBase64 == null || publicKeyDerBase64.isBlank()) {
            throw new IllegalStateException("Artifact signing trust is not configured");
        }
        var encoded = Base64.getDecoder().decode(publicKeyDerBase64.trim());
        var publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(encoded));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        String canonical = artifactDigest + "\n" + manifestDigest + "\n" + keyId;
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signatureBase64));
    }
}
