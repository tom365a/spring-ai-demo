package com.demo.cs.config;

import com.demo.cs.infrastructure.vector.SimpleFileVectorStore;
import com.demo.cs.infrastructure.vector.LexicalFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.core.io.ClassPathResource;
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
    @Primary
    @ConditionalOnProperty(name = "app.vector.backend", havingValue = "lexical")
    VectorStore lexicalStore(ObjectMapper mapper, AppProperties props) {
        return new LexicalFileStore(mapper, Path.of(props.uploadDir(), "lexical-store.json"),
                Path.of(props.uploadDir(), "vector-store.json"));
    }

    @Bean
    ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /**
     * 本地 ONNX 中文嵌入（bge-small-zh-v1.5，int8 量化，512 维）。
     * 模型随包交付，运行时不请求任何外部嵌入服务，也不产生调用费用。
     * OpenAI 自动配置也会注册一个 EmbeddingModel，因此这里标 @Primary 明确优先本地；
     * 需要改用 OpenAI 嵌入时设 app.vector.embedding=openai，本 bean 不会创建。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.vector.embedding", havingValue = "local", matchIfMissing = true)
    EmbeddingModel localEmbeddingModel() throws Exception {
        // 模型有 23 MB，不进 git 仓库，靠 scripts/fetch-model 拉取。缺文件时底层会抛一个很难懂的异常，
        // 这里先拦一道，直接告诉使用者该跑哪个脚本。
        ClassPathResource tokenizer = new ClassPathResource("models/bge-small-zh/tokenizer.json");
        ClassPathResource onnx = new ClassPathResource("models/bge-small-zh/model.onnx");
        if (!tokenizer.exists() || !onnx.exists()) {
            throw new IllegalStateException(
                    "本地嵌入模型缺失（models/bge-small-zh/）。该模型不随仓库分发，请先执行一次：\n"
                            + "  powershell -File scripts/fetch-model.ps1    （或 bash scripts/fetch-model.sh）\n"
                            + "无法联网时可改用其它后端：--app.vector.embedding=openai 或 --app.vector.backend=lexical");
        }
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        model.setTokenizerResource(tokenizer);
        model.setModelResource(onnx);
        model.setResourceCacheDirectory(System.getProperty("java.io.tmpdir") + "/spring-ai-onnx-cache");
        model.afterPropertiesSet();
        return model;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.vector.backend", havingValue = "simple")
    VectorStore simpleVectorStore(EmbeddingModel embeddingModel, AppProperties props) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        return new SimpleFileVectorStore(store, Path.of(props.uploadDir(), "vector-store.json"));
    }
}
