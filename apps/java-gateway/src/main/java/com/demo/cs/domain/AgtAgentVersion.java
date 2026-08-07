package com.demo.cs.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agt_agent_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agt_agent_ver", columnNames = {"agent_code", "version"})
})
public class AgtAgentVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_code", nullable = false, length = 64)
    private String agentCode;

    @Column(nullable = false)
    private int version;

    @Lob
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt = Instant.now();

    @Column(name = "published_by", length = 64)
    private String publishedBy;

    @Column(length = 500)
    private String remark;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAgentCode() { return agentCode; }
    public void setAgentCode(String agentCode) { this.agentCode = agentCode; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
