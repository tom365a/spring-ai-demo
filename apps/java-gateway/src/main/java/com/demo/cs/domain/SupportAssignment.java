package com.demo.cs.domain;
import jakarta.persistence.*;
import java.time.Instant;

/** 一次人工接管。HUMAN 模式下由真人坐席认领并回复；SIMULATED 保留给无人值守演示。 */
@Entity @Table(name="cs_support_assignment")
public class SupportAssignment {
 public static final String WAITING="WAITING";   // 已转人工，等待坐席认领
 public static final String ACTIVE="ACTIVE";     // 坐席已认领，正在对话
 public static final String CLOSED="CLOSED";     // 坐席结束接管

 @Id public String sessionId;
 /** SIMULATED 模式下为扮演人工的子 Agent code；HUMAN 模式下为 null。 */
 public String agentCode;
 public String mode="SIMULATED";
 @Column(length=16) public String status=WAITING;
 @Column(length=64) public String operatorId;
 @Column(length=64) public String operatorName;
 public Instant assignedAt=Instant.now();
 public Instant claimedAt;
 public Instant lastCustomerAt;
 public Instant lastOperatorAt;
 public Instant closedAt;
}
