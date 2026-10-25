package io.github.assami.audit.ai;

/** Every slot of the assistant is taken. Asking again a moment later works. */
public class AuditAssistantBusyException extends RuntimeException {
    public AuditAssistantBusyException() {
        super("The audit assistant is busy, try again shortly");
    }
}
