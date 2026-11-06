package com.ledgerly.identity.internal.web;

import com.ledgerly.identity.internal.infrastructure.JwtKeys;
import com.ledgerly.shared.api.JwksApi;
import com.ledgerly.shared.api.model.JwkSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public half of the signing key at /.well-known/jwks.json. */
@RestController
class JwksController implements JwksApi {

    private final JwtKeys keys;

    JwksController(JwtKeys keys) {
        this.keys = keys;
    }

    @Override
    public ResponseEntity<JwkSet> getJwks() {
        Map<String, Object> jwk = keys.key().toPublicJWK().toJSONObject();
        return ResponseEntity.ok(new JwkSet(List.of(jwk)));
    }
}
