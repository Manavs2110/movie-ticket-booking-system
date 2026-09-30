package com.moviebooking.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    @Test
    void roundTripKeepsMicrosecondPrecision() {
        Instant at = Instant.parse("2026-09-29T09:19:43.387659Z");
        CursorCodec.Cursor c = CursorCodec.decode(CursorCodec.encode(at, 42));
        assertThat(c.createdAt()).isEqualTo(at);
        assertThat(c.id()).isEqualTo(42);
    }

    @Test
    void cursorIsUrlSafe() {
        assertThat(CursorCodec.encode(Instant.parse("2026-09-29T09:19:43Z"), 1)).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void garbageIsRejectedAsValidationError() {
        assertThatThrownBy(() -> CursorCodec.decode("not-a-cursor!!"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
