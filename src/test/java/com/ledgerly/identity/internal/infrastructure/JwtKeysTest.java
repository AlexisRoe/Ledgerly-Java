package com.ledgerly.identity.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtKeysTest {

    static AuthProperties props(String priv, String pub) {
        return new AuthProperties(null, null, null, 5, null, priv, pub);
    }

    @Test
    void generatesAKeyWhenAllowedAndNothingIsConfigured() {
        JwtKeys keys = JwtKeys.create(props(null, null), true);
        assertThat(keys.key().isPrivate()).isTrue();
        assertThat(keys.keyId()).isNotBlank();
    }

    @Test
    void failsFastWhenNotAllowedAndNothingIsConfigured() {
        assertThatThrownBy(() -> JwtKeys.create(props(null, null), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-private-key");
        assertThatThrownBy(() -> JwtKeys.create(props("", " "), false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loadsConfiguredPemKeysAndDerivesAStableKeyId() throws Exception {
        var generated = JwtKeys.generate().key();
        String priv = pem("PRIVATE KEY", generated.toECPrivateKey().getEncoded());
        String pub = pem("PUBLIC KEY", generated.toECPublicKey().getEncoded());
        JwtKeys first = JwtKeys.create(props(priv, pub), false);
        JwtKeys second = JwtKeys.create(props(priv, pub), false);
        assertThat(first.keyId()).isEqualTo(second.keyId()).isNotBlank();
        assertThat(first.key().toECPublicKey()).isEqualTo(generated.toECPublicKey());
    }

    @Test
    void garbageKeysAreRejected() {
        assertThatThrownBy(() -> JwtKeys.create(props("not a key", "nor this"), false))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
                + "-----";
    }
}
