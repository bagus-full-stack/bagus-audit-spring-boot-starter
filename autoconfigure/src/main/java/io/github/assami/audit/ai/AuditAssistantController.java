package io.github.assami.audit.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/audit/ask}: a question in, an answer and the entries it came from out. Needs AUDIT_READ. */
@RestController
@RequestMapping("${audit.api.base-path:/api/audit}/ask")
public class AuditAssistantController {

    private static final Logger log = LoggerFactory.getLogger(AuditAssistantController.class);

    public record AskRequest(@NotBlank @Size(max = 1000) String question) {}

    private final AuditAssistant assistant;

    public AuditAssistantController(AuditAssistant assistant) {
        this.assistant = assistant;
    }

    // POST, so the question stays out of access logs and URLs, but it only reads: it needs the read role.
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('AUDIT_READ') or hasRole('AUDIT_ADMIN')")
    public AuditAssistant.Answer ask(@Valid @RequestBody AskRequest request) {
        return assistant.ask(request.question());
    }

    @ExceptionHandler(AuditAssistantBusyException.class)
    ProblemDetail busy(AuditAssistantBusyException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(AuditAssistantUnavailableException.class)
    ProblemDetail unavailable(AuditAssistantUnavailableException ex) {
        log.warn("Audit assistant failed: {}", ex.getCause().toString());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }
}
