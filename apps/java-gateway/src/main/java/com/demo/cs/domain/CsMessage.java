package com.demo.cs.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cs_message")
public class CsMessage {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;
    @Column(nullable = false, length = 32)
    private String role;
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    @Column(name = "agent_name", length = 64)
    private String agentName;
    @Lob
    @Column(name = "citations_json", columnDefinition = "TEXT")
    private String citationsJson;
    @Lob
    @Column(name = "tool_calls_json", columnDefinition = "TEXT")
    private String toolCallsJson;
    @Lob
    @Column(name = "attachments_json", columnDefinition = "TEXT")
    private String attachmentsJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getCitationsJson() { return citationsJson; }
    public void setCitationsJson(String citationsJson) { this.citationsJson = citationsJson; }
    public String getToolCallsJson() { return toolCallsJson; }
    public void setToolCallsJson(String toolCallsJson) { this.toolCallsJson = toolCallsJson; }
    public String getAttachmentsJson() { return attachmentsJson; }
    public void setAttachmentsJson(String attachmentsJson) { this.attachmentsJson = attachmentsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
