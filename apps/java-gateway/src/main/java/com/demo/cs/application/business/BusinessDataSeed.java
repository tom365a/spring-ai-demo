package com.demo.cs.application.business;

import com.demo.cs.domain.BizLogisticsTrace;
import com.demo.cs.domain.BizOrder;
import com.demo.cs.infrastructure.persistence.BizLogisticsTraceRepository;
import com.demo.cs.infrastructure.persistence.BizOrderRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 演示订单与物流轨迹的种子数据。按主键逐条判断，重复启动不会重建，
 * 也不会覆盖运行中产生的状态（例如演示时已取消的订单保持已取消）。
 */
@Component
@Order(20)
public class BusinessDataSeed implements ApplicationRunner {

    private final BizOrderRepository orders;
    private final BizLogisticsTraceRepository traces;

    public BusinessDataSeed(BizOrderRepository orders, BizLogisticsTraceRepository traces) {
        this.orders = orders;
        this.traces = traces;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        order("ORD20260730001", "u_001", BizOrder.PENDING_SHIPMENT, "299.00", "无线耳机 x1", null, null, "2026-07-28T10:00:00Z");
        order("ORD20260730002", "u_001", BizOrder.SHIPPED, "159.00", "手机壳 x2", "SF1234567890", "顺丰", "2026-07-25T08:00:00Z");
        order("ORD20260730003", "u_002", BizOrder.COMPLETED, "89.00", "数据线 x1", null, null, "2026-07-20T12:00:00Z");

        trace("SF1234567890", "顺丰", "2026-07-29T14:00:00Z", "快件已揽收", "深圳");
        trace("SF1234567890", "顺丰", "2026-07-30T09:30:00Z", "运输中", "广州转运中心");
        trace("SF1234567890", "顺丰", "2026-07-30T18:00:00Z", "派送中", "北京");
    }

    private void order(String id, String userId, String status, String amount, String items,
                       String trackingNo, String carrier, String createdAt) {
        if (orders.existsById(id)) return;
        BizOrder o = new BizOrder();
        o.setOrderId(id);
        o.setUserId(userId);
        o.setStatus(status);
        o.setAmount(new BigDecimal(amount));
        o.setItems(items);
        o.setTrackingNo(trackingNo);
        o.setCarrier(carrier);
        o.setCreatedAt(Instant.parse(createdAt));
        orders.save(o);
    }

    private void trace(String trackingNo, String carrier, String at, String status, String location) {
        if (traces.existsByTrackingNo(trackingNo) && !traces.findByTrackingNoOrderByOccurredAtAsc(trackingNo).isEmpty()
                && traces.findByTrackingNoOrderByOccurredAtAsc(trackingNo).stream()
                    .anyMatch(t -> t.getOccurredAt().equals(Instant.parse(at)))) return;
        BizLogisticsTrace t = new BizLogisticsTrace();
        t.setTrackingNo(trackingNo);
        t.setCarrier(carrier);
        t.setOccurredAt(Instant.parse(at));
        t.setStatus(status);
        t.setLocation(location);
        traces.save(t);
    }
}
