package io.github.assami.audit.autoconfigure;

import io.github.assami.audit.ai.AuditAssistant;
import io.github.assami.audit.ai.AuditAssistantController;
import io.github.assami.audit.ai.AuditAssistantIndexer;
import io.github.assami.audit.condition.ConditionalOnAuditEnabled;
import io.github.assami.audit.port.AuditRepository;
import io.github.assami.audit.properties.AuditProperties;
import io.github.assami.audit.service.AuditService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * The audit assistant: ask a question about the audit log in plain language. Active only with audit-starter-ai
 * on the classpath, a chat model and an embedding model configured (Ollama by default), and
 * {@code audit.ai.enabled} not set to false.
 */
@AutoConfiguration(after = AuditAutoConfiguration.class, afterName = {
        "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
        "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration"})
@ConditionalOnAuditEnabled
@ConditionalOnClass({ChatClient.class, VectorStore.class})
@ConditionalOnBean({ChatModel.class, EmbeddingModel.class, AuditService.class})
@ConditionalOnProperty(name = "audit.ai.enabled", havingValue = "true", matchIfMissing = true)
public class AuditAiAutoConfiguration {

    /** In-memory index. Define your own VectorStore bean to keep it across restarts. */
    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore auditVectorStore(EmbeddingModel embeddings) {
        return SimpleVectorStore.builder(embeddings).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditAssistant auditAssistant(AuditService service, VectorStore index, ChatModel model,
                                         AuditProperties properties) {
        return new AuditAssistant(service, index, ChatClient.builder(model).build(), properties.getAi().getTopK());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public AuditAssistantIndexer auditAssistantIndexer(VectorStore index, AuditRepository repository,
                                                       AuditProperties properties) {
        return new AuditAssistantIndexer(index, repository, properties.getAi().getBackfill());
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditAssistantController auditAssistantController(AuditAssistant assistant) {
        return new AuditAssistantController(assistant);
    }
}
