package com.ledgerly.identity.internal.infrastructure;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The ES256 signing key pair, with a key id so verifiers can pick the right key. */
public record JwtKeys(ECKey key) {

    public static JwtKeys create(AuthProperties props, boolean generationAllowed) {
        String priv = props.jwtPrivateKey();
        String pub = props.jwtPublicKey();
        if (priv == null || priv.isBlank() || pub == null || pub.isBlank()) {
            if (!generationAllowed) {
                throw new IllegalStateException(
                        "ledgerly.auth.jwt-private-key and jwt-public-key must be set outside the local and test profiles");
            }
            return generate();
        }
        return fromPem(priv, pub);
    }

    static JwtKeys generate() {
        try {
            return new JwtKeys(new ECKeyGenerator(Curve.P_256)
                    .keyID(UUID.randomUUID().toString())
                    .generate());
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a signing key", e);
        }
    }

    static JwtKeys fromPem(String privatePem, String publicPem) {
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            ECPrivateKey privateKey =
                    (ECPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(decode(privatePem)));
            ECPublicKey publicKey = (ECPublicKey) factory.generatePublic(new X509EncodedKeySpec(decode(publicPem)));
            ECKey key = new ECKey.Builder(Curve.P_256, publicKey)
                    .privateKey(privateKey)
                    .keyIDFromThumbprint()
                    .build();
            return new JwtKeys(key);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT key configuration", e);
        }
    }

    private static byte[] decode(String pem) {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    public String keyId() {
        String kid = key.getKeyID();
        return kid == null ? "" : kid;
    }

    @SuppressWarnings("NullAway")
    public @Nullable ECKey publicJwk() {
        return key.toPublicJWK();
    }
}
