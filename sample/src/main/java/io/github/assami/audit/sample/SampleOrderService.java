package io.github.assami.audit.sample;

import io.github.assami.audit.model.AuditLevel;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.service.AuditService;
import org.springframework.stereotype.Service;

/**
 * Example service demonstrating how to use {@link AuditService}
 * programmatically within your domain logic.
 */
@Service
public class SampleOrderService {

    private final AuditService auditService;

    public SampleOrderService(AuditService auditService) {
        this.auditService = auditService;
    }

    public void placeOrder(String orderId, String userId) {
        // Business logic would go here...

        // Record an audit log entry
        auditService.create(
                AuditLog.builder()
                        .action("ORDER_PLACED")
                        .level(AuditLevel.INFO)
                        .payload("{\"orderId\":\"" + orderId + "\",\"userId\":\"" + userId + "\"}")
                        .createdBy(userId)
        );
    }

    public void cancelOrder(String orderId, String userId) {
        auditService.create(
                AuditLog.builder()
                        .action("ORDER_CANCELLED")
                        .level(AuditLevel.WARN)
                        .payload("{\"orderId\":\"" + orderId + "\"}")
                        .createdBy(userId)
        );
    }
}
