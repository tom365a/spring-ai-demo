package com.demo.cs.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="cfg_resource_version",uniqueConstraints=@UniqueConstraint(columnNames={"resourceId","versionNumber"}))
public class ManagedResourceVersion {
 @Id public String id;
 @Column(nullable=false) public String resourceId;
 @Column(nullable=false) public int versionNumber;
 @Lob @Column(nullable=false,columnDefinition="TEXT") public String definitionJson;
 public String credentialRef;
 public Instant createdAt=Instant.now();
}
