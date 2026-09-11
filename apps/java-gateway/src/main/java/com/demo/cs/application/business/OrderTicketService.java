package com.demo.cs.application.business;

import com.demo.cs.domain.BizLogisticsTrace;
import com.demo.cs.domain.BizOrder;
import com.demo.cs.domain.BizTicket;
import com.demo.cs.infrastructure.persistence.BizLogisticsTraceRepository;
import com.demo.cs.infrastructure.persistence.BizOrderRepository;
import com.demo.cs.infrastructure.persistence.BizTicketRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单与工单的真实业务服务：状态落库、重启保留、取消在悲观锁下做一次性状态迁移。
 * 返回结构与改造前的内存实现保持一致（ok / message / order / ticketId），
 * 因此提示词、工具 schema 与既有回归断言都不需要改。
 *
 * 这里的数据是演示数据。要接客户自己的订单系统，走资源配置里的 HTTP 自定义工具或 MCP，
 * 不需要改这个类。
 */
@Service
public class OrderTicketService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault());
    private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BizOrderRepository orders;
    private final BizTicketRepository tickets;
    private final BizLogisticsTraceRepository traces;

    public OrderTicketService(BizOrderRepository orders, BizTicketRepository tickets, BizLogisticsTraceRepository traces) {
        this.orders = orders;
        this.tickets = tickets;
        this.traces = traces;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> queryOrder(String orderId, String userId) {
        BizOrder order = orders.findById(nullToEmpty(orderId)).orElse(null);
        if (order == null) return fail("订单不存在");
        if (!order.getUserId().equals(nullToEmpty(userId))) return fail("无权查看该订单");
        return Map.of("ok", true, "order", view(order));
    }

    /**
     * 取消是一次性状态迁移：只有「待发货」可取消，锁内复查状态，
     * 因此并发的两次确认只有一次能改到数据，第二次得到明确的业务拒绝。
     */
    @Transactional
    public Map<String, Object> cancelOrder(String orderId, String userId, String reason) {
        BizOrder order = orders.lockById(nullToEmpty(orderId)).orElse(null);
        if (order == null) return fail("订单不存在");
        if (!order.getUserId().equals(nullToEmpty(userId))) return fail("无权取消该订单");
        if (!order.isCancellable()) return fail("订单当前状态不可取消：" + order.getStatus());

        order.setStatus(BizOrder.CANCELLED);
        order.setCancelReason(reason);
        order.setCancelledAt(java.time.Instant.now());
        orders.save(order);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "订单已取消");
        out.put("order", view(order));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> queryLogistics(String trackingNo) {
        List<BizLogisticsTrace> rows = traces.findByTrackingNoOrderByOccurredAtAsc(nullToEmpty(trackingNo));
        if (rows.isEmpty()) return fail("运单不存在或暂无轨迹");
        List<Map<String, String>> nodes = rows.stream().map(t -> Map.of(
                "time", t.getOccurredAt().toString(),
                "status", t.getStatus(),
                "location", t.getLocation()
        )).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("trackingNo", trackingNo);
        out.put("carrier", rows.getFirst().getCarrier());
        out.put("traces", nodes);
        return out;
    }

    @Transactional
    public Map<String, Object> createTicket(String userId, String category, String description, String priority, String orderId) {
        if (nullToEmpty(userId).isBlank()) return fail("缺少用户标识，无法建单");
        if (nullToEmpty(description).isBlank()) return fail("请先补充问题描述");

        BizTicket ticket = new BizTicket();
        ticket.setUserId(userId);
        ticket.setCategory(nullToEmpty(category).isBlank() ? "其他" : category);
        ticket.setDescription(description);
        ticket.setPriority(nullToEmpty(priority).isBlank() ? "P3" : priority);
        ticket.setStatus(BizTicket.OPEN);
        if (orderId != null && !orderId.isBlank()) ticket.setOrderId(orderId);

        BizTicket saved = null;
        for (int attempt = 0; attempt < 3 && saved == null; attempt++) {
            ticket.setTicketNo(nextTicketNo());
            try {
                saved = tickets.saveAndFlush(ticket);
            } catch (DataIntegrityViolationException collision) {
                if (attempt == 2) throw collision;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ticketId", saved.getTicketNo());
        out.put("message", "工单已创建");
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> tickets() {
        return tickets.findAllByOrderByIdDesc().stream().map(this::view).toList();
    }

    /** 单号由日期加随机后缀构成，唯一约束兜底；不再用时间戳，避免同毫秒并发碰撞。 */
    private String nextTicketNo() {
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) suffix.append(BASE36.charAt(RANDOM.nextInt(BASE36.length())));
        return "TK" + DAY.format(java.time.Instant.now()) + suffix;
    }

    private Map<String, Object> view(BizOrder order) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.getOrderId());
        out.put("userId", order.getUserId());
        out.put("status", order.getStatus());
        out.put("amount", order.getAmount());
        out.put("items", order.getItems());
        out.put("createdAt", order.getCreatedAt().toString());
        out.put("cancellable", order.isCancellable());
        if (order.getTrackingNo() != null) out.put("trackingNo", order.getTrackingNo());
        if (order.getCancelReason() != null) out.put("cancelReason", order.getCancelReason());
        return out;
    }

    private Map<String, Object> view(BizTicket ticket) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketId", ticket.getTicketNo());
        out.put("userId", ticket.getUserId());
        out.put("category", ticket.getCategory());
        out.put("description", ticket.getDescription());
        out.put("priority", ticket.getPriority());
        out.put("status", ticket.getStatus());
        out.put("createdAt", ticket.getCreatedAt().toString());
        if (ticket.getOrderId() != null) out.put("orderId", ticket.getOrderId());
        return out;
    }

    private static Map<String, Object> fail(String message) {
        return Map.of("ok", false, "message", message);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
