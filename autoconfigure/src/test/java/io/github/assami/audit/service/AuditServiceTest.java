package io.github.assami.audit.service;

import io.github.assami.audit.adapter.inmemory.InMemoryAuditRepository;
import io.github.assami.audit.event.AuditLogCreatedEvent;
import io.github.assami.audit.event.AuditLogDeletedEvent;
import io.github.assami.audit.event.AuditLogErasedEvent;
import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.tenancy.TenantContext;
import io.github.assami.audit.port.AuditRepository;
import io.github.assami.audit.properties.AuditProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    private AuditRepository repository;
    private ApplicationEventPublisher publisher;
    private AuditService service;
    private List<Object> capturedEvents;

    @BeforeEach
    void setUp() {
        repository     = new InMemoryAuditRepository();
        capturedEvents = new ArrayList<>();
        publisher      = capturedEvents::add;

        AuditProperties props = new AuditProperties();
        props.setName("test-service");

        service = new AuditService(repository, publisher, props, Optional.empty());
    }

    @Test
    void create_persistsAndPublishesEvent() {
        AuditLog created = service.create(
                AuditLog.builder().action("LOGIN").payload("{}"));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getServiceName()).isEqualTo("test-service");
        assertThat(capturedEvents).hasSize(1)
                .first().isInstanceOf(AuditLogCreatedEvent.class);
    }

    @Test
    void findById_returnsCreated() {
        AuditLog created = service.create(AuditLog.builder().action("X"));
        AuditLog found   = service.findById(created.getId());

        assertThat(found.getId()).isEqualTo(created.getId());
    }

    @Test
    void findById_notFound_throws() {
        assertThatThrownBy(() -> service.findById("nope"))
                .isInstanceOf(AuditNotFoundException.class);
    }

    @Test
    void delete_softDeletesAndPublishesEvent() {
        AuditLog created = service.create(AuditLog.builder().action("X"));
        service.delete(created.getId());

        assertThatThrownBy(() -> service.findById(created.getId()))
                .isInstanceOf(AuditNotFoundException.class);

        assertThat(capturedEvents)
                .filteredOn(e -> e instanceof AuditLogDeletedEvent)
                .hasSize(1);
    }

    @Test
    void erasePersonalData_callsGdprEraserAndPublishesEvent() {
        var eraser = mock(io.github.assami.audit.port.AuditGdprEraser.class);
        AuditProperties props = new AuditProperties();
        props.setName("svc");
        AuditService svc = new AuditService(repository, capturedEvents::add, props, Optional.of(eraser));

        AuditLog created = svc.create(AuditLog.builder().action("A").payload("PII"));
        svc.erasePersonalData(created.getId());

        verify(eraser).beforeErase(created.getId());
        assertThat(capturedEvents)
                .filteredOn(e -> e instanceof AuditLogErasedEvent)
                .hasSize(1);
    }

    @Test
    void tenantContext_isolatesQueryFindDeleteAndErase() {
        TenantContext.set("A");
        AuditLog a = service.create(AuditLog.builder().action("X"));
        TenantContext.set("B");
        try {
            // B asks for tenant A explicitly: the context wins.
            AuditPage page = service.query(AuditQuery.builder().tenantId("A").pageSize(10).build());
            assertThat(page.content()).isEmpty();
            assertThatThrownBy(() -> service.findById(a.getId())).isInstanceOf(AuditNotFoundException.class);
            assertThatThrownBy(() -> service.delete(a.getId())).isInstanceOf(AuditNotFoundException.class);
            assertThatThrownBy(() -> service.erasePersonalData(a.getId())).isInstanceOf(AuditNotFoundException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void createBatch_persistsAll() {
        List<AuditLog.Builder> builders = List.of(
                AuditLog.builder().action("A"),
                AuditLog.builder().action("B"),
                AuditLog.builder().action("C")
        );
        List<AuditLog> saved = service.createBatch(builders);

        assertThat(saved).hasSize(3);
        assertThat(capturedEvents).hasSize(3)
                .allMatch(e -> e instanceof AuditLogCreatedEvent);
    }
}
