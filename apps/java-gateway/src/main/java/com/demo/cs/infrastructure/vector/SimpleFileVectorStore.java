package com.demo.cs.infrastructure.vector;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SimpleFileVectorStore implements VectorStore {

    private final SimpleVectorStore delegate;
    private final Path persistPath;

    public SimpleFileVectorStore(SimpleVectorStore delegate, Path persistPath) {
        this.delegate = delegate;
        this.persistPath = persistPath;
        try {
            if (Files.exists(persistPath)) {
                delegate.load(persistPath.toFile());
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            Files.createDirectories(persistPath.getParent());
            delegate.save(persistPath.toFile());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
        save();
    }

    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
        save();
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
        save();
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return delegate.similaritySearch(request);
    }
}
