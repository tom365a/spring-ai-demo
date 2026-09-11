package com.demo.cs;

import com.demo.cs.infrastructure.vector.LexicalFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LexicalFileStoreTest {
    @TempDir Path dir;

    private LexicalFileStore store() {
        return new LexicalFileStore(new ObjectMapper(), dir.resolve("lexical.json"), dir.resolve("vector.json"));
    }

    @Test void ranksChineseAndEnglishWithoutEmbeddingAndLabelsResults() {
        var store = store();
        store.add(List.of(new Document("退货期限为七天", Map.of("title", "退货政策")),
                new Document("物流需要三天", Map.of("title", "配送")),
                new Document("Returns accepted within seven days", Map.of())));
        var hits = store.similaritySearch(SearchRequest.builder().query("退货期限").similarityThreshold(0.5).topK(1).build());
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().getText()).contains("七天");
        assertThat(hits.getFirst().getMetadata()).containsEntry("retrieval", "lexical").containsEntry("score", 1.0);
        assertThat(store.similaritySearch(SearchRequest.builder().query("RETURNS").build())).hasSize(1);
        assertThat(store.similaritySearch(SearchRequest.builder().query("火星航班").build())).isEmpty();
    }

    @Test void persistsAcrossRestartAndDeletion() {
        var store = store();
        var doc = new Document("退货", Map.of("doc_id", "existing"));
        store.add(List.of(doc));
        var restarted = store();
        assertThat(restarted.similaritySearch(SearchRequest.builder().query("退货").build())).hasSize(1);
        restarted.delete(List.of(doc.getId()));
        assertThat(store().similaritySearch(SearchRequest.builder().query("退货").build())).isEmpty();
    }

    @Test void migratesExistingTextsWithoutTouchingOldVectors() throws Exception {
        String old = "{\"old-id\":{\"id\":\"old-id\",\"text\":\"保修一年\",\"embedding\":[0.1,0.2],\"metadata\":{\"doc_id\":\"old-doc\"}}}";
        Files.writeString(dir.resolve("vector.json"), old);
        var hits = store().similaritySearch(SearchRequest.builder().query("保修").build());
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().getId()).isEqualTo("old-id");
        assertThat(hits.getFirst().getMetadata()).containsEntry("doc_id", "old-doc");
        assertThat(Files.readString(dir.resolve("vector.json"))).isEqualTo(old);
        assertThat(Files.readString(dir.resolve("lexical.json"))).doesNotContain("embedding");
    }
}
