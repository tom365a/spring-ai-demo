package com.demo.cs.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 物流轨迹节点。原先是硬编码常量，现按运单号落库查询。 */
@Entity
@Table(name = "biz_logistics_trace", indexes = @Index(name = "idx_trace_tracking", columnList = "trackingNo"))
public class BizLogisticsTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "biz_trace_seq")
    @SequenceGenerator(name = "biz_trace_seq", sequenceName = "biz_trace_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 64)
    private String trackingNo;

    @Column(nullable = false, length = 32)
    private String carrier;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 64)
    private String status;

    @Column(nullable = false, length = 64)
    private String location;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTrackingNo() { return trackingNo; }
    public void setTrackingNo(String trackingNo) { this.trackingNo = trackingNo; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
