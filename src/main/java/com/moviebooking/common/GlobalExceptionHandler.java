package com.moviebooking.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Constraint name → friendly code: the safety net when a service pre-check lost a race. */
    private static final Map<String, ErrorCode> CONSTRAINT_CODES = Map.of(
            "ux_booking_active_hold", ErrorCode.ACTIVE_HOLD_EXISTS,
            "ex_show_no_overlap", ErrorCode.SHOW_OVERLAP,
            "ux_payment_idempotency_key", ErrorCode.IDEMPOTENCY_KEY_REUSED,
            "app_user_email_key", ErrorCode.EMAIL_TAKEN,
            "seat_lock_pkey", ErrorCode.SEATS_UNAVAILABLE);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiError> handleApp(AppException ex, HttpServletRequest req) {
        return respond(ex.code(), ex.getMessage(), ex.details(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalid(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors().forEach(ge -> fields.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        return respond(ErrorCode.VALIDATION_ERROR, "Request validation failed", fields, req);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(r -> fields.put(
                r.getMethodParameter().getParameterName(),
                r.getResolvableErrors().isEmpty() ? "invalid" : r.getResolvableErrors().get(0).getDefaultMessage()));
        return respond(ErrorCode.VALIDATION_ERROR, "Request validation failed", fields, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> fields.put(v.getPropertyPath().toString(), v.getMessage()));
        return respond(ErrorCode.VALIDATION_ERROR, "Request validation failed", fields, req);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest req) {
        String message = ex instanceof HttpMessageNotReadableException ? "Malformed JSON body" : ex.getMessage();
        return respond(ErrorCode.VALIDATION_ERROR, message, Map.of(), req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        String constraint = constraintName(ex);
        ErrorCode code = constraint == null ? ErrorCode.CONFLICT : CONSTRAINT_CODES.getOrDefault(constraint, ErrorCode.CONFLICT);
        log.info("Integrity violation on constraint {} → {}", constraint, code);
        return respond(code, "Request conflicts with existing data", Map.of(), req);
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ApiError> handleBusy(Exception ex, HttpServletRequest req) {
        log.warn("Database unavailable: {}", ex.getMessage());
        return respond(ErrorCode.SERVICE_BUSY, "Service busy, please retry", Map.of(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException ex, HttpServletRequest req) {
        return respond(ErrorCode.FORBIDDEN, "Access denied", Map.of(), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoRoute(NoResourceFoundException ex, HttpServletRequest req) {
        return respond(ErrorCode.NOT_FOUND, "No endpoint " + req.getMethod() + " " + req.getRequestURI(), Map.of(), req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return respond(ErrorCode.NOT_FOUND, ex.getMessage(), Map.of(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return respond(ErrorCode.INTERNAL_ERROR, "Unexpected error", Map.of(), req);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code, String message, Map<String, Object> details,
                                                    HttpServletRequest req) {
        return ResponseEntity.status(code.status()).body(ApiError.of(code, message, details, req.getRequestURI()));
    }

    static String constraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return psql.getServerErrorMessage().getConstraint();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
