package com.demo.cs.config;

import com.demo.cs.infrastructure.vector.SimpleFileVectorStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;

@Configuration
public class AiConfig {

    @Bean
    ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.vector.backend", havingValue = "simple")
    VectorStore simpleVectorStore(EmbeddingModel embeddingModel, AppProperties props) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        return new SimpleFileVectorStore(store, Path.of(props.uploadDir(), "vector-store.json"));
    }
}
