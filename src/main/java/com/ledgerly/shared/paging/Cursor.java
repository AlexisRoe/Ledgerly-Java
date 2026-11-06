package com.ledgerly.shared.paging;

import com.ledgerly.shared.error.InvalidRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An opaque position in a keyset-paged list: the sort timestamp and the id of the last row seen. Encoded as
 * Base64url JSON so clients treat it as a token and never build one themselves.
 */
public record Cursor(Instant timestamp, UUID id) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The Base64url text handed to clients as {@code nextCursor}. */
    public String encode() {
        String json = JSON.writeValueAsString(Map.of("t", timestamp.toString(), "id", id.toString()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidRequest (400) for anything that is not a cursor this class produced */
    public static Cursor decode(String token) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            JsonNode node = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
            return new Cursor(
                    Instant.parse(node.get("t").asString()),
                    UUID.fromString(node.get("id").asString()));
        } catch (RuntimeException e) {
            // Bad Base64, bad JSON, missing fields, bad timestamp or UUID: all the same to the caller.
            throw new InvalidRequest("cursor", "Invalid cursor");
        }
    }
}
