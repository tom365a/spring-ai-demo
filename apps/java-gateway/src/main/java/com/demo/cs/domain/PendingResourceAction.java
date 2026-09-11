package com.demo.cs.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="cfg_pending_action")
public class PendingResourceAction {
 @Id public String id;
 @Column(nullable=false) public String sessionId;
 @Column(nullable=false) public String state;
 @Lob @Column(columnDefinition="TEXT") public String snapshotJson;
 public Instant expiresAt;
 public Instant createdAt=Instant.now();
}
