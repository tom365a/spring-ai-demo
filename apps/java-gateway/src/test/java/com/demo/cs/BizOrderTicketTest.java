package com.demo.cs;

import com.demo.cs.application.business.OrderTicketService;
import com.demo.cs.domain.BizLogisticsTrace;
import com.demo.cs.domain.BizOrder;
import com.demo.cs.infrastructure.persistence.BizLogisticsTraceRepository;
import com.demo.cs.infrastructure.persistence.BizOrderRepository;
import com.demo.cs.infrastructure.persistence.BizTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 订单与工单已从内存 mock 改为真实持久化：状态迁移、并发一次性、单号唯一。 */
@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(OrderTicketService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BizOrderTicketTest {

    @Autowired OrderTicketService business;
    @Autowired BizOrderRepository orders;
    @Autowired BizTicketRepository tickets;
    @Autowired BizLogisticsTraceRepository traces;

    @BeforeEach
    void reset() {
        tickets.deleteAll();
        traces.deleteAll();
        orders.deleteAll();
        orders.save(order("ORD_PENDING", "u_001", BizOrder.PENDING_SHIPMENT, null));
        orders.save(order("ORD_SHIPPED", "u_001", BizOrder.SHIPPED, "SF999"));
        BizLogisticsTrace t = new BizLogisticsTrace();
        t.setTrackingNo("SF999"); t.setCarrier("顺丰");
        t.setOccurredAt(Instant.parse("2026-07-29T14:00:00Z"));
        t.setStatus("快件已揽收"); t.setLocation("深圳");
        traces.save(t);
    }

    private BizOrder order(String id, String user, String status, String tracking) {
        BizOrder o = new BizOrder();
        o.setOrderId(id); o.setUserId(user); o.setStatus(status);
        o.setAmount(new BigDecimal("299.00")); o.setItems("无线耳机 x1");
        o.setTrackingNo(tracking);
        if (tracking != null) o.setCarrier("顺丰");
        return o;
    }

    @Test
    void cancelIsAOneWayStateTransitionPersistedToTheDatabase() {
        assertThat(business.queryOrder("ORD_PENDING", "u_001")).containsEntry("ok", true);

        Map<String, Object> done = business.cancelOrder("ORD_PENDING", "u_001", "演示取消");
        assertThat(done).containsEntry("ok", true).containsEntry("message", "订单已取消");

        // 落库，不是内存状态
        BizOrder stored = orders.findById("ORD_PENDING").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BizOrder.CANCELLED);
        assertThat(stored.getCancelReason()).isEqualTo("演示取消");
        assertThat(stored.getCancelledAt()).isNotNull();
        assertThat(stored.isCancellable()).isFalse();

        // 再次取消得到明确的业务拒绝，而不是又成功一次
        assertThat(business.cancelOrder("ORD_PENDING", "u_001", "再来一次"))
                .containsEntry("ok", false)
                .containsEntry("message", "订单当前状态不可取消：已取消");
    }

    @Test
    void shippedOrderAndForeignUserAreRejected() {
        assertThat(business.cancelOrder("ORD_SHIPPED", "u_001", "试试"))
                .containsEntry("ok", false)
                .containsEntry("message", "订单当前状态不可取消：已发货");
        assertThat(business.cancelOrder("ORD_PENDING", "u_002", "越权"))
                .containsEntry("ok", false).containsEntry("message", "无权取消该订单");
        assertThat(business.queryOrder("ORD_PENDING", "u_002"))
                .containsEntry("ok", false).containsEntry("message", "无权查看该订单");
        assertThat(business.queryOrder("NOPE", "u_001"))
                .containsEntry("ok", false).containsEntry("message", "订单不存在");
        // 被拒绝的尝试不得改动数据
        assertThat(orders.findById("ORD_PENDING").orElseThrow().getStatus()).isEqualTo(BizOrder.PENDING_SHIPMENT);
    }

    @Test
    void concurrentCancelsSucceedExactlyOnce() throws Exception {
        int threads = 8;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), rejected = new AtomicInteger();
        var futures = IntStream.range(0, threads).mapToObj(i -> pool.submit(() -> {
            start.await();
            Map<String, Object> r = business.cancelOrder("ORD_PENDING", "u_001", "并发 " + i);
            if (Boolean.TRUE.equals(r.get("ok"))) ok.incrementAndGet(); else rejected.incrementAndGet();
            return null;
        })).toList();
        start.countDown();
        for (var f : futures) f.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(orders.findById("ORD_PENDING").orElseThrow().getStatus()).isEqualTo(BizOrder.CANCELLED);
    }

    @Test
    void ticketsArePersistedWithUniqueNumbers() {
        Set<String> numbers = IntStream.range(0, 25)
                .mapToObj(i -> business.createTicket("u_001", "破损少件", "包裹破损 " + i, "P3", null).get("ticketId").toString())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(numbers).hasSize(25);
        assertThat(tickets.count()).isEqualTo(25);

        List<Map<String, Object>> listed = business.tickets();
        assertThat(listed).hasSize(25);
        assertThat(listed.getFirst()).containsEntry("status", "open").containsEntry("priority", "P3");

        assertThat(business.createTicket("", "其他", "缺用户", "P3", null)).containsEntry("ok", false);
        assertThat(business.createTicket("u_001", "其他", "  ", "P3", null)).containsEntry("ok", false);
        assertThat(tickets.count()).isEqualTo(25);
    }

    @Test
    void logisticsComesFromTheTableNotAConstant() {
        Map<String, Object> found = business.queryLogistics("SF999");
        assertThat(found).containsEntry("ok", true).containsEntry("carrier", "顺丰");
        assertThat((List<?>) found.get("traces")).hasSize(1);
        assertThat(business.queryLogistics("SF_UNKNOWN"))
                .containsEntry("ok", false).containsEntry("message", "运单不存在或暂无轨迹");
    }
}
