package com.moviebooking.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(String code, String message, Map<String, Object> details, String path, Instant timestamp) {

    public static ApiError of(ErrorCode code, String message, Map<String, Object> details, String path) {
        return new ApiError(code.name(), message, details == null ? Map.of() : details, path, Instant.now());
    }
}
