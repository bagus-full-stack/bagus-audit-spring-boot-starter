package io.github.assami.audit.rest;

import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.exception.MissingTenantException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;

/**
 * Global exception handler producing RFC 9457 Problem Details responses.
 */
@RestControllerAdvice
public class AuditExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String TYPE_BASE = "https://assami.dev/problems/audit/";

    @ExceptionHandler(AuditNotFoundException.class)
    ProblemDetail handleNotFound(AuditNotFoundException ex, WebRequest req) {
        return problem(HttpStatus.NOT_FOUND, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(MissingTenantException.class)
    ProblemDetail handleMissingTenant(MissingTenantException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage());
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("errorCode", code);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
