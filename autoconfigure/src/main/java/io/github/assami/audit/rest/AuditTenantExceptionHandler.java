package io.github.assami.audit.rest;

import io.github.assami.audit.exception.MissingTenantException;
import io.github.assami.audit.exception.TenantMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problem responses for the tenant errors, which {@code AuditTenantFilter} raises before any controller runs.
 * A filter has no controller, so these two cannot sit in the advice that is limited to the audit controllers.
 * It is safe to apply everywhere: it only knows this starter's own exception types.
 */
@RestControllerAdvice
public class AuditTenantExceptionHandler {

    @ExceptionHandler(MissingTenantException.class)
    ProblemDetail handleMissingTenant(MissingTenantException ex) {
        return AuditExceptionHandler.problem(HttpStatus.BAD_REQUEST, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(TenantMismatchException.class)
    ProblemDetail handleTenantMismatch(TenantMismatchException ex) {
        return AuditExceptionHandler.problem(HttpStatus.FORBIDDEN, ex.getErrorCode(), ex.getMessage());
    }
}
