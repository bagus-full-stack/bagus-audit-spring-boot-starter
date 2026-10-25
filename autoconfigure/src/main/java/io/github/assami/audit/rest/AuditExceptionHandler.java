package io.github.assami.audit.rest;

import io.github.assami.audit.exception.AuditNotFoundException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;

/**
 * Exception handler producing RFC 9457 Problem Details responses for the audit controllers only.
 * Without the package limit it would also answer for the host application's own controllers, turning
 * their {@code IllegalArgumentException} into a 400 with the raw message.
 */
@RestControllerAdvice(basePackages = {"io.github.assami.audit.rest", "io.github.assami.audit.ai"})
public class AuditExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String TYPE_BASE = "https://assami.dev/problems/audit/";

    @ExceptionHandler(AuditNotFoundException.class)
    ProblemDetail handleNotFound(AuditNotFoundException ex, WebRequest req) {
        return problem(HttpStatus.NOT_FOUND, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage());
    }

    // ── helper ───────────────────────────────────────────────────────────────

    static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("errorCode", code);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
