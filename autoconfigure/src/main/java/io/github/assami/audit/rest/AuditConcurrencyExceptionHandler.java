package io.github.assami.audit.rest;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Two admins changing the same entry at once (delete and erase both touch its version): the second one lost the
 * race and can ask again. Registered by the JPA auto-configuration only, because the exception comes from Spring's
 * data access layer, which a host without JPA may not have.
 */
@RestControllerAdvice(basePackages = {"io.github.assami.audit.rest", "io.github.assami.audit.ai"})
public class AuditConcurrencyExceptionHandler {

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleConflict(OptimisticLockingFailureException ex) {
        return AuditExceptionHandler.problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The entry was changed at the same time, try again");
    }
}
