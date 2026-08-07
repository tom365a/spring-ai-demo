package com.demo.cs.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cs_session")
public class CsSession {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;
    @Column(length = 32)
    private String channel = "web";
    @Column(length = 32)
    private String status = "active";
    @Lob
    @Column(columnDefinition = "TEXT")
    private String summary;
    @Column(name = "last_intent", length = 64)
    private String lastIntent;
    @Column(name = "last_agent", length = 64)
    private String lastAgent;
    @Lob
    @Column(name = "confirmation_payload", columnDefinition = "TEXT")
    private String confirmationPayload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getLastIntent() { return lastIntent; }
    public void setLastIntent(String lastIntent) { this.lastIntent = lastIntent; }
    public String getLastAgent() { return lastAgent; }
    public void setLastAgent(String lastAgent) { this.lastAgent = lastAgent; }
    public String getConfirmationPayload() { return confirmationPayload; }
    public void setConfirmationPayload(String confirmationPayload) { this.confirmationPayload = confirmationPayload; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
