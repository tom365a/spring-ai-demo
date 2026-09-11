package com.demo.cs.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="cs_conversation_event",indexes=@Index(name="idx_conversation_event_session",columnList="sessionId,id"))
public class ConversationEvent {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false,length=64) public String sessionId;
 @Column(length=64) public String turnId;
 @Column(nullable=false,length=40) public String type;
 @Column(length=64) public String agentCode;
 @Lob @Column(columnDefinition="TEXT") public String dataJson;
 @Column(nullable=false) public Instant createdAt=Instant.now();
}
