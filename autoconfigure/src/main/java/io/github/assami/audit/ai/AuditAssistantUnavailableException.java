package io.github.assami.audit.ai;

/** The language model or the embedding model did not answer. The cause stays in the server log. */
public class AuditAssistantUnavailableException extends RuntimeException {
    public AuditAssistantUnavailableException(Throwable cause) {
        super("The audit assistant is unavailable", cause);
    }
}
