package com.moviebooking.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/** Opaque keyset cursor: base64url("<created_at ISO>|<id>"). */
public final class CursorCodec {

    public record Cursor(Instant createdAt, long id) {
    }

    private CursorCodec() {
    }

    public static String encode(Instant createdAt, long id) {
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf('|');
            if (sep < 0) {
                throw invalid();
            }
            return new Cursor(Instant.parse(raw.substring(0, sep)), Long.parseLong(raw.substring(sep + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static AppException invalid() {
        return new AppException(ErrorCode.VALIDATION_ERROR, "Invalid cursor");
    }
}
