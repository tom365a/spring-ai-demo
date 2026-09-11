package com.demo.cs.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 演示用售后工单。单号由数据库序列生成，避免时间戳并发碰撞。 */
@Entity
@Table(name = "biz_ticket")
public class BizTicket {

    public static final String OPEN = "open";
    public static final String CLOSED = "closed";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "biz_ticket_seq")
    @SequenceGenerator(name = "biz_ticket_seq", sequenceName = "biz_ticket_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String ticketNo;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 32)
    private String category;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 8)
    private String priority;

    @Column(nullable = false, length = 16)
    private String status = OPEN;

    @Column(length = 64)
    private String orderId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTicketNo() { return ticketNo; }
    public void setTicketNo(String ticketNo) { this.ticketNo = ticketNo; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
