package com.demo.cs.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "kb_document")
public class KbDocument {
    @Id
    @Column(length = 64)
    private String id;
    @Column(nullable = false)
    private String title;
    private String source;
    @Column(name = "chunk_count")
    private int chunkCount;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
