package io.github.assami.audit.autoconfigure;

import io.github.assami.audit.ai.AuditAssistant;
import io.github.assami.audit.ai.AuditAssistantTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The assistant's default index is an explicit trial opt-in, like the in-memory repository. */
class AuditAiAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class, AuditAiAutoConfiguration.class,
                    SecurityAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(ChatModel.class, AuditAssistantTest.RecordingModel::new)
            .withBean(EmbeddingModel.class, AuditAssistantTest.WordEmbeddings::new)
            .withPropertyValues("audit.in-memory=true");

    @Test
    void withoutAnIndexAndWithoutTheOptIn_theApplicationDoesNotStart() {
        runner.run(ctx -> assertThat(ctx).hasFailed().getFailure().hasMessageContaining("audit.ai.in-memory-index"));
    }

    @Test
    void withTheOptIn_theAssistantUsesTheInMemoryIndex() {
        runner.withPropertyValues("audit.ai.in-memory-index=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(AuditAssistant.class).hasSingleBean(VectorStore.class);
        });
    }

    @Test
    void aHostVectorStore_needsNoOptIn() {
        runner.withBean("hostIndex", VectorStore.class, () -> SimpleVectorStore.builder(new AuditAssistantTest.WordEmbeddings()).build())
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(AuditAssistant.class).hasBean("hostIndex"));
    }

    @Test
    void switchedOff_noAssistantExists() {
        runner.withPropertyValues("audit.ai.enabled=false").run(ctx -> assertThat(ctx).doesNotHaveBean(AuditAssistant.class));
    }
}
