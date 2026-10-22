package io.github.assami.audit.rest;

import io.github.assami.audit.model.AuditLevel;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * REST controller exposing the audit API.
 * <p>
 * Base path defaults to {@code /api/audit} and is configurable via
 * {@code audit.api.base-path}.
 * </p>
 */
@RestController
@RequestMapping("${audit.api.base-path:/api/audit}")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    // ── POST /api/audit ──────────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('AUDIT_WRITE') or hasRole('AUDIT_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AuditLogResponse create(@Valid @RequestBody CreateAuditRequest req) {
        String actor = currentActor();
        AuditLog saved = service.create(
                AuditLog.builder()
                        .action(req.action())
                        .level(req.level() != null ? req.level() : AuditLevel.INFO)
                        .payload(req.payload())
                        .createdBy(actor)
                        .lastModifiedBy(actor)
        );
        return AuditLogResponse.from(saved);
    }

    // ── POST /api/audit/batch ────────────────────────────────────────────────

    @PostMapping(path = "/batch",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('AUDIT_WRITE') or hasRole('AUDIT_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public List<AuditLogResponse> createBatch(@Valid @RequestBody BatchAuditRequest req) {
        String actor = currentActor();
        List<AuditLog.Builder> builders = req.entries().stream()
                .map(e -> AuditLog.builder()
                        .action(e.action())
                        .level(e.level() != null ? e.level() : AuditLevel.INFO)
                        .payload(e.payload())
                        .createdBy(actor)
                        .lastModifiedBy(actor))
                .toList();

        // All-or-nothing: the batch is one transaction, so it never partially fails.
        return service.createBatch(builders).stream().map(AuditLogResponse::from).toList();
    }

    /** The authenticated caller, never a name supplied in the request body. */
    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    // ── GET /api/audit ───────────────────────────────────────────────────────

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('AUDIT_READ') or hasRole('AUDIT_ADMIN')")
    public AuditPageResponse getAll(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) AuditLevel level,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size
    ) {
        AuditQuery query = AuditQuery.builder()
                .tenantId(tenantId)
                .serviceName(serviceName)
                .action(action)
                .level(level)
                .from(from)
                .to(to)
                .cursor(cursor)
                .pageSize(size)
                .build();

        AuditPage page = service.query(query);
        List<AuditLogResponse> content = page.content().stream().map(AuditLogResponse::from).toList();
        return new AuditPageResponse(content, page.nextCursor(), page.hasNext());
    }

    // ── GET /api/audit/{id} ──────────────────────────────────────────────────

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('AUDIT_READ') or hasRole('AUDIT_ADMIN')")
    public AuditLogResponse getById(@PathVariable String id) {
        return AuditLogResponse.from(service.findById(id));
    }

    // ── DELETE /api/audit/{id} ───────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AUDIT_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    // ── DELETE /api/audit/{id}/personal-data ────────────────────────────────

    @DeleteMapping("/{id}/personal-data")
    @PreAuthorize("hasRole('AUDIT_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void erasePersonalData(@PathVariable String id) {
        service.erasePersonalData(id);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record CreateAuditRequest(
            @NotBlank @Size(max = 255) String action,
            AuditLevel level,
            @Size(max = 65_536) String payload
    ) {}

    public record BatchAuditRequest(
            @NotEmpty @Size(max = 100) List<@Valid @NotNull CreateAuditRequest> entries
    ) {}

    public record AuditPageResponse(
            List<AuditLogResponse> content,
            String nextCursor,
            boolean hasNext
    ) {}

    public record AuditLogResponse(
            String id,
            String tenantId,
            String serviceName,
            String action,
            AuditLevel level,
            String payload,
            String createdBy,
            String lastModifiedBy,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            boolean gdprErased
    ) {
        static AuditLogResponse from(AuditLog l) {
            return new AuditLogResponse(
                    l.getId(), l.getTenantId(), l.getServiceName(), l.getAction(),
                    l.getLevel(), l.getPayload(), l.getCreatedBy(), l.getLastModifiedBy(),
                    l.getCreatedAt(), l.getUpdatedAt(), l.getVersion(),
                    l.isGdprErased()
            );
        }
    }
}
