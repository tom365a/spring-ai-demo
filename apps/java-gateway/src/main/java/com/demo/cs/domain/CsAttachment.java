package com.demo.cs.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cs_attachment")
public class CsAttachment {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "session_id", length = 64)
    private String sessionId;
    @Column(name = "original_name")
    private String originalName;
    @Column(name = "content_type", length = 128)
    private String contentType;
    @Column(name = "file_path", nullable = false)
    private String filePath;
    @Column(name = "public_url")
    private String publicUrl;
    @Column(name = "size_bytes")
    private long sizeBytes;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
