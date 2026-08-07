package com.demo.cs.application.knowledge;

import com.demo.cs.agent.model.AgentModels.Citation;
import com.demo.cs.api.dto.ApiDtos.KnowledgeDocResponse;
import com.demo.cs.api.dto.ApiDtos.SearchHit;
import com.demo.cs.config.AppProperties;
import com.demo.cs.domain.KbDocument;
import com.demo.cs.infrastructure.persistence.KbDocumentRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class KnowledgeService {

    private final KbDocumentRepository docRepo;
    private final VectorStore vectorStore;
    private final AppProperties props;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    public KnowledgeService(KbDocumentRepository docRepo, VectorStore vectorStore, AppProperties props) {
        this.docRepo = docRepo;
        this.vectorStore = vectorStore;
        this.props = props;
    }

    @Transactional
    public KnowledgeDocResponse ingest(String title, String content, String source) {
        String docId = newId("doc_");
        List<Document> chunks = splitter.apply(List.of(new Document(content, Map.of(
                "doc_id", docId,
                "title", title,
                "source", source != null ? source : "manual"
        ))));
        vectorStore.add(chunks);

        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setTitle(title);
        doc.setSource(source != null ? source : "manual");
        doc.setChunkCount(chunks.size());
        doc.setCreatedAt(Instant.now());
        docRepo.save(doc);

        return toResponse(doc);
    }

    public List<SearchHit> search(String query, Integer topK) {
        int k = topK != null && topK > 0 ? topK : props.rag().topK();
        List<Document> docs = retrieveDocuments(query, k);
        List<SearchHit> hits = new ArrayList<>();
        for (Document d : docs) {
            Map<String, Object> meta = d.getMetadata() != null ? d.getMetadata() : Map.of();
            double score = meta.get("distance") instanceof Number n ? 1.0 - n.doubleValue()
                    : meta.get("score") instanceof Number s ? s.doubleValue() : 0.0;
            hits.add(new SearchHit(d.getText(), score, meta));
        }
        return hits;
    }

    public List<Document> retrieveDocuments(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(props.rag().scoreThreshold())
                .build();
        return vectorStore.similaritySearch(request);
    }

    public List<Citation> toCitations(List<Document> docs) {
        List<Citation> out = new ArrayList<>();
        for (Document d : docs) {
            Map<String, Object> meta = d.getMetadata() != null ? d.getMetadata() : Map.of();
            String docId = String.valueOf(meta.getOrDefault("doc_id", meta.getOrDefault("id", "")));
            String title = String.valueOf(meta.getOrDefault("title", "知识片段"));
            double score = meta.get("distance") instanceof Number n ? 1.0 - n.doubleValue()
                    : meta.get("score") instanceof Number s ? s.doubleValue() : 0.0;
            out.add(new Citation(docId, title, d.getText(), score, "vector", new LinkedHashMap<>(meta)));
        }
        return out;
    }

    @Transactional
    public BootstrapResult bootstrapSamples() {
        Path dir = Path.of(props.knowledgeSampleDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return new BootstrapResult(0, List.of());
        }
        List<KnowledgeDocResponse> ingested = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".md")).toList()) {
                String content = Files.readString(file);
                String title = file.getFileName().toString().replace(".md", "");
                KnowledgeDocResponse doc = ingest(title, content, "bootstrap:" + file.getFileName());
                ingested.add(doc);
            }
        } catch (IOException e) {
            throw new IllegalStateException("bootstrap failed: " + e.getMessage(), e);
        }
        return new BootstrapResult(ingested.size(), ingested);
    }

    public List<KnowledgeDocResponse> listDocs() {
        return docRepo.findAll().stream().map(this::toResponse).toList();
    }

    public long countDocs() {
        return docRepo.count();
    }

    private KnowledgeDocResponse toResponse(KbDocument doc) {
        return new KnowledgeDocResponse(
                doc.getId(), doc.getTitle(), doc.getSource(), doc.getChunkCount(), doc.getCreatedAt()
        );
    }

    private String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public record BootstrapResult(int ingested, List<KnowledgeDocResponse> docs) {}
}
