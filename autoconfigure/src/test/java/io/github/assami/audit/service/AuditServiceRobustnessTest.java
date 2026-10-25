package io.github.assami.audit.service;

import io.github.assami.audit.adapter.inmemory.InMemoryAuditRepository;
import io.github.assami.audit.event.AuditLogCreatedEvent;
import io.github.assami.audit.event.AuditLogErasedEvent;
import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.port.AuditGdprEraser;
import io.github.assami.audit.properties.AuditProperties;
import io.github.assami.audit.tenancy.TenantContext;
import io.github.assami.audit.support.PaginationAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class AuditServiceRobustnessTest {

    private InMemoryAuditRepository repo;
    private List<Object> events;
    private AuditProperties props;

    @BeforeEach
    void setUp() {
        repo = new InMemoryAuditRepository();
        events = new ArrayList<>();
        props = new AuditProperties();
        props.setName("svc");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private AuditService service(Optional<AuditGdprEraser> eraser) {
        return new AuditService(repo, events::add, props, eraser);
    }

    @Test
    void eraserHookFailure_abortsTheErasure_andPublishesNothing() {
        AuditGdprEraser eraser = mock(AuditGdprEraser.class);
        AuditService svc = service(Optional.of(eraser));
        AuditLog created = svc.create(AuditLog.builder().action("A").payload("PII"));
        doThrow(new IllegalStateException("remote system down")).when(eraser).beforeErase(created.getId());
        events.clear();

        assertThatThrownBy(() -> svc.erasePersonalData(created.getId())).isInstanceOf(IllegalStateException.class);

        assertThat(svc.findById(created.getId()).getPayload()).isEqualTo("PII");
        assertThat(events).noneMatch(e -> e instanceof AuditLogErasedEvent);
    }

    @Test
    void unknownIds_areNotFound_forEveryOperation() {
        AuditService svc = service(Optional.empty());

        assertThatThrownBy(() -> svc.findById("nope")).isInstanceOf(AuditNotFoundException.class);
        assertThatThrownBy(() -> svc.delete("nope")).isInstanceOf(AuditNotFoundException.class);
        assertThatThrownBy(() -> svc.erasePersonalData("nope")).isInstanceOf(AuditNotFoundException.class);
        assertThat(events).isEmpty();
    }

    @Test
    void deletingTwice_secondTimeIsNotFound_andPublishesOneEvent() {
        AuditService svc = service(Optional.empty());
        AuditLog created = svc.create(AuditLog.builder().action("A"));

        svc.delete(created.getId());

        assertThatThrownBy(() -> svc.delete(created.getId())).isInstanceOf(AuditNotFoundException.class);
        assertThat(events.stream().filter(e -> !(e instanceof AuditLogCreatedEvent))).hasSize(1);
    }

    @Test
    void failingListener_doesNotTurnACommittedWriteIntoAnError() {
        AuditService svc = new AuditService(repo, e -> { throw new IllegalStateException("listener down"); },
                props, Optional.empty());

        AuditLog created = svc.create(AuditLog.builder().action("A"));
        List<AuditLog> batch = svc.createBatch(List.of(AuditLog.builder().action("B"), AuditLog.builder().action("C")));
        svc.erasePersonalData(created.getId());
        svc.delete(created.getId());

        assertThat(batch).hasSize(2);
        assertThat(PaginationAssertions.countAll(repo)).isEqualTo(2);
    }

    @Test
    void emptyBatch_savesNothingAndPublishesNothing() {
        assertThat(service(Optional.empty()).createBatch(List.of())).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    void missingAction_failsBeforeAnythingIsStored() {
        AuditService svc = service(Optional.empty());

        assertThatThrownBy(() -> svc.create(AuditLog.builder())).isInstanceOf(IllegalStateException.class);
        assertThat(PaginationAssertions.countAll(repo)).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void create_stampsTheServiceNameAndTheCurrentTenant() {
        TenantContext.set("acme");

        AuditLog created = service(Optional.empty()).create(AuditLog.builder().action("A").tenantId("forged"));

        assertThat(created.getServiceName()).isEqualTo("svc");
        assertThat(created.getTenantId()).isEqualTo("acme");
    }

    @Test
    void create_withoutTenantContext_keepsTheTenantNull() {
        assertThat(service(Optional.empty()).create(AuditLog.builder().action("A")).getTenantId()).isNull();
    }

    @Test
    void batch_stampsEveryEntryWithTheTenant_andPublishesOneEventEach() {
        TenantContext.set("acme");

        List<AuditLog> saved = service(Optional.empty()).createBatch(
                List.of(AuditLog.builder().action("A"), AuditLog.builder().action("B")));

        assertThat(saved).allMatch(l -> "acme".equals(l.getTenantId()));
        assertThat(events).hasSize(2);
    }

    @Test
    void otherTenantsEntry_cannotBeReadDeletedOrErased() {
        AuditService svc = service(Optional.empty());
        TenantContext.set("A");
        AuditLog mine = svc.create(AuditLog.builder().action("X"));
        TenantContext.set("B");

        assertThatThrownBy(() -> svc.findById(mine.getId())).isInstanceOf(AuditNotFoundException.class);
        assertThatThrownBy(() -> svc.delete(mine.getId())).isInstanceOf(AuditNotFoundException.class);
        assertThatThrownBy(() -> svc.erasePersonalData(mine.getId())).isInstanceOf(AuditNotFoundException.class);

        TenantContext.set("A");
        assertThat(svc.findById(mine.getId()).isDeleted()).isFalse();
    }

    @Test
    void entryWithoutTenant_isHiddenFromEveryTenant() {
        AuditService svc = service(Optional.empty());
        AuditLog global = svc.create(AuditLog.builder().action("X"));
        TenantContext.set("acme");

        assertThatThrownBy(() -> svc.findById(global.getId())).isInstanceOf(AuditNotFoundException.class);
    }
}
