package io.github.assami.audit.native_;

import io.github.assami.audit.model.AuditLevel;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.rest.AuditController;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.lang.NonNull;

/**
 * GraalVM Native Image runtime hints for the audit library.
 * Registers all public domain classes for reflection and serialization.
 */
public class AuditRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(@NonNull RuntimeHints hints, ClassLoader classLoader) {

        // Domain model
        for (Class<?> type : new Class<?>[]{ AuditLog.class, AuditPage.class, AuditQuery.class, AuditLevel.class }) {
            hints.reflection().registerType(type, MemberCategory.values());
        }

        // REST DTOs (records)
        for (Class<?> type : new Class<?>[] {
                AuditController.CreateAuditRequest.class,
                AuditController.AuditLogResponse.class,
                AuditController.AuditPageResponse.class,
                AuditController.BatchAuditRequest.class,
                AuditController.BatchAuditResponse.class
        }) {
            hints.reflection().registerType(type, MemberCategory.values());
        }

        // Additional metadata
        hints.resources().registerPattern("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        hints.resources().registerPattern("META-INF/additional-spring-configuration-metadata.json");
    }
}
