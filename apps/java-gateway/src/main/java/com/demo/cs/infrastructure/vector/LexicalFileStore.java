package com.demo.cs.infrastructure.vector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Local keyword retrieval. Scores are query-token coverage, never embedding similarity. */
public class LexicalFileStore implements VectorStore {
    private static final Pattern WORD = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private final ObjectMapper mapper;
    private final Path path;
    private Map<String, Entry> entries = new LinkedHashMap<>();

    public record Entry(String id, String text, Map<String, Object> metadata) {}

    public LexicalFileStore(ObjectMapper mapper, Path path, Path legacyVectorPath) {
        this.mapper = mapper;
        this.path = path.toAbsolutePath();
        try {
            if (Files.exists(this.path)) {
                entries = mapper.readValue(this.path.toFile(), new TypeReference<LinkedHashMap<String, Entry>>() {});
            } else if (Files.exists(legacyVectorPath)) {
                // Reuse original text only; do not mix embeddings from different providers.
                var tree = mapper.readTree(legacyVectorPath.toFile());
                tree.fields().forEachRemaining(field -> {
                    var node = field.getValue();
                    var metadata = mapper.convertValue(node.path("metadata"), new TypeReference<Map<String, Object>>() {});
                    entries.put(field.getKey(), new Entry(field.getKey(), node.path("text").asText(), metadata));
                });
                persist(entries);
            }
        } catch (IOException e) {
            throw new IllegalStateException("本地关键词索引读取失败，请检查索引文件", e);
        }
    }

    @Override
    public synchronized void add(List<Document> documents) {
        var next = new LinkedHashMap<>(entries);
        documents.forEach(d -> next.put(d.getId(), new Entry(d.getId(), d.getText(), new LinkedHashMap<>(d.getMetadata()))));
        persist(next);
        entries = next;
    }

    @Override
    public synchronized void delete(List<String> ids) {
        var next = new LinkedHashMap<>(entries);
        ids.forEach(next::remove);
        persist(next);
        entries = next;
    }

    @Override
    public void delete(Filter.Expression filter) {
        throw new UnsupportedOperationException("关键词索引仅支持按 ID 删除");
    }

    @Override
    public synchronized List<Document> similaritySearch(SearchRequest request) {
        if (request.getFilterExpression() != null) {
            throw new UnsupportedOperationException("关键词索引暂不支持元数据过滤");
        }
        var query = tokens(request.getQuery());
        if (query.isEmpty()) return List.of();
        return entries.values().stream().map(entry -> {
            var terms = tokens(entry.text() + " " + entry.metadata().getOrDefault("title", ""));
            double score = query.stream().filter(terms::contains).count() / (double) query.size();
            var metadata = new LinkedHashMap<>(entry.metadata());
            metadata.remove("distance");
            metadata.put("score", score);
            metadata.put("retrieval", "lexical");
            return Document.builder().id(entry.id()).text(entry.text()).metadata(metadata).score(score).build();
        }).filter(d -> d.getScore() > 0 && d.getScore() >= request.getSimilarityThreshold())
                .sorted(Comparator.comparingDouble((Document d) -> d.getScore()).reversed().thenComparing(Document::getId))
                .limit(request.getTopK()).toList();
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            var token = matcher.group();
            var points = token.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN && points.length > 1) {
                for (int i = 0; i < points.length - 1; i++) result.add(new String(points, i, 2));
            } else result.add(token);
        }
        return result;
    }

    private void persist(Map<String, Entry> next) {
        Path temp = null;
        try {
            Files.createDirectories(path.getParent());
            temp = Files.createTempFile(path.getParent(), "lexical-", ".tmp");
            mapper.writeValue(temp.toFile(), next);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            throw new IllegalStateException("本地关键词索引保存失败，本次操作未生效", e);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }
}
