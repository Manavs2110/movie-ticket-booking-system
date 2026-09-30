package com.moviebooking.common;

import java.util.Map;

/** Base for every expected business error; the handler turns it into {@link ApiError}. */
public class AppException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public AppException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public AppException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static AppException notFound(String what, Object id) {
        return new AppException(ErrorCode.NOT_FOUND, what + " " + id + " not found");
    }
}
