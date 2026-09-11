package com.demo.cs.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** 演示用订单主数据。真实持久化：状态迁移落库、重启保留、取消走悲观锁。 */
@Entity
@Table(name = "biz_order")
public class BizOrder {

    /** 待发货 -> 可取消；已发货/已完成/已取消 -> 终态或不可取消。 */
    public static final String PENDING_SHIPMENT = "待发货";
    public static final String SHIPPED = "已发货";
    public static final String COMPLETED = "已完成";
    public static final String CANCELLED = "已取消";

    @Id
    @Column(length = 64)
    private String orderId;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 256)
    private String items;

    @Column(length = 64)
    private String trackingNo;

    @Column(length = 32)
    private String carrier;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(length = 512)
    private String cancelReason;

    private Instant cancelledAt;

    /** 派生，不落库：避免与 status 漂移。 */
    @Transient
    public boolean isCancellable() {
        return PENDING_SHIPMENT.equals(status);
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public String getTrackingNo() { return trackingNo; }
    public void setTrackingNo(String trackingNo) { this.trackingNo = trackingNo; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
}
