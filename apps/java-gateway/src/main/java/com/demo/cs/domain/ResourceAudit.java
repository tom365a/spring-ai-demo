package com.demo.cs.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="cfg_resource_audit")
public class ResourceAudit {
 @Id public String id;
 public String resourceId;
 public String action;
 public int revision;
 public Integer versionNumber;
 public String actorContext;
 @Lob @Column(columnDefinition="TEXT") public String differenceJson;
 public Instant createdAt=Instant.now();
}
