package io.github.assami.audit.adapter.inmemory;

import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.model.AuditCursor;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.port.AuditRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * In-memory {@link AuditRepository} adapter.
 * <p>
 * <strong>Not suitable for production.</strong> This adapter is auto-configured
 * as a fallback when no JPA adapter is on the classpath. All data is lost on
 * application restart.
 * </p>
 */
public class InMemoryAuditRepository implements AuditRepository {

    private final Map<String, AuditLog> store = new ConcurrentHashMap<>();

    @Override
    public AuditLog save(AuditLog log) {
        store.put(log.getId(), log);
        return log;
    }

    @Override
    public List<AuditLog> saveAll(List<AuditLog> logs) {
        logs.forEach(l -> store.put(l.getId(), l));
        return logs;
    }

    @Override
    public Optional<AuditLog> findById(String id) {
        return Optional.ofNullable(store.get(id))
                .filter(l -> !l.isDeleted());
    }

    @Override
    public AuditPage findAll(AuditQuery query) {
        Stream<AuditLog> stream = store.values().stream()
                .filter(l -> !l.isDeleted());

        if (query.getTenantId() != null)    stream = stream.filter(l -> query.getTenantId().equals(l.getTenantId()));
        if (query.getServiceName() != null) stream = stream.filter(l -> query.getServiceName().equals(l.getServiceName()));
        if (query.getAction() != null)      stream = stream.filter(l -> query.getAction().equals(l.getAction()));
        if (query.getLevel() != null)       stream = stream.filter(l -> query.getLevel() == l.getLevel());
        if (query.getFrom() != null)        stream = stream.filter(l -> !l.getCreatedAt().isBefore(query.getFrom()));
        if (query.getTo() != null)          stream = stream.filter(l -> !l.getCreatedAt().isAfter(query.getTo()));

        // Apply keyset cursor (created_at DESC, id DESC)
        if (query.getCursor() != null) {
            AuditCursor keyset = AuditCursor.decode(query.getCursor());
            stream = stream.filter(l ->
                    l.getCreatedAt().isBefore(keyset.createdAt()) ||
                    (l.getCreatedAt().equals(keyset.createdAt()) && l.getId().compareTo(keyset.id()) < 0)
            );
        }

        List<AuditLog> sorted = stream
                .sorted(Comparator.<AuditLog, Instant>comparing(AuditLog::getCreatedAt).reversed()
                        .thenComparing(Comparator.comparing(AuditLog::getId).reversed()))
                .collect(Collectors.toCollection(ArrayList::new));

        int pageSize = query.getPageSize();
        boolean hasNext = sorted.size() > pageSize;
        List<AuditLog> page = sorted.subList(0, Math.min(sorted.size(), pageSize));

        String nextCursor = null;
        if (hasNext) {
            AuditLog last = page.get(page.size() - 1);
            nextCursor = new AuditCursor(last.getCreatedAt(), last.getId()).encode();
        }

        return AuditPage.of(page, nextCursor);
    }

    @Override
    public void softDelete(String id) {
        AuditLog log = store.get(id);
        if (log == null) throw new AuditNotFoundException(id);
        store.put(id, log.softDelete(Instant.now()));
    }

    @Override
    public void gdprErase(String id) {
        AuditLog log = store.get(id);
        if (log == null) throw new AuditNotFoundException(id);
        store.put(id, log.markGdprErased(Instant.now()));
    }

    @Override
    public long count() {
        return store.values().stream().filter(l -> !l.isDeleted()).count();
    }
}
