package com.ledgerly.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.shared.error.InvalidRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorTest {

    @Test
    void roundTrips() {
        var cursor = new Cursor(Instant.parse("2026-11-05T10:15:30.123456Z"), UUID.randomUUID());
        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void tokenIsUrlSafe() {
        String token = new Cursor(Instant.now(), UUID.randomUUID()).encode();
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "!!!", "not-base64-%%", "e30", "bm90IGpzb24"})
    void junkIsA400(String junk) {
        assertThatThrownBy(() -> Cursor.decode(junk)).isInstanceOf(InvalidRequest.class);
    }

    @Test
    void validBase64OfWrongJsonIsA400() {
        String wrong = Base64.getUrlEncoder()
                .encodeToString("{\"t\":\"yesterday\",\"id\":\"nope\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Cursor.decode(wrong)).isInstanceOf(InvalidRequest.class);
    }
}
