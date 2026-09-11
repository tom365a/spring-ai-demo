package com.demo.cs.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="cfg_resource")
public class ManagedResource {
 @Id public String id;
 @Column(nullable=false,unique=true,length=100) public String code;
 @Column(nullable=false,length=16) public String kind;
 @Column(nullable=false,length=128) public String name;
 @Column(length=1000) public String description;
 @Column(nullable=false) public boolean enabled;
 @Column(nullable=false) public boolean deleted;
 public Integer publishedVersion;
 @Column(nullable=false) public int draftRevision;
 @Lob @Column(nullable=false,columnDefinition="TEXT") public String draftJson;
 public String credentialRef;
 public String connectionStatus;
 public Integer lastTestRevision;
 public Instant updatedAt=Instant.now();
}
