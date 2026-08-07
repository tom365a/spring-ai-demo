package com.demo.cs.infrastructure.tools;

import com.demo.cs.domain.CsToolInvocation;
import com.demo.cs.infrastructure.persistence.CsToolInvocationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OrderTicketTools {

    private static final ThreadLocal<String> SESSION_CONTEXT = new ThreadLocal<>();

    private final CsToolInvocationRepository auditRepo;
    private final ObjectMapper objectMapper;

    /** In-memory mock tickets for debug API. */
    private static final List<Map<String, Object>> MOCK_TICKETS = new ArrayList<>();
    private static final Map<String, Map<String, Object>> MOCK_ORDERS = new ConcurrentHashMap<>();

    static {
        MOCK_ORDERS.put("ORD20260730001", Map.of(
                "orderId", "ORD20260730001",
                "userId", "u_001",
                "status", "待发货",
                "amount", 299.0,
                "items", "无线耳机 x1",
                "createdAt", "2026-07-28T10:00:00Z",
                "cancellable", true
        ));
        MOCK_ORDERS.put("ORD20260730002", Map.of(
                "orderId", "ORD20260730002",
                "userId", "u_001",
                "status", "已发货",
                "amount", 159.0,
                "items", "手机壳 x2",
                "trackingNo", "SF1234567890",
                "createdAt", "2026-07-25T08:00:00Z",
                "cancellable", false
        ));
        MOCK_ORDERS.put("ORD20260730003", Map.of(
                "orderId", "ORD20260730003",
                "userId", "u_002",
                "status", "已完成",
                "amount", 89.0,
                "items", "数据线 x1",
                "createdAt", "2026-07-20T12:00:00Z",
                "cancellable", false
        ));
    }

    public OrderTicketTools(CsToolInvocationRepository auditRepo, ObjectMapper objectMapper) {
        this.auditRepo = auditRepo;
        this.objectMapper = objectMapper;
    }

    public void setSessionId(String sessionId) {
        if (sessionId == null) {
            SESSION_CONTEXT.remove();
        } else {
            SESSION_CONTEXT.set(sessionId);
        }
    }

    public void clearSessionId() {
        SESSION_CONTEXT.remove();
    }

    public static List<Map<String, Object>> mockTickets() {
        return List.copyOf(MOCK_TICKETS);
    }

    @Tool(description = "根据订单号查询当前用户的订单状态与基本信息")
    public String queryOrder(
            @ToolParam(description = "订单号，如 ORD20260730001") String orderId,
            @ToolParam(description = "当前用户 ID") String userId
    ) {
        return execute("query_order", Map.of("orderId", orderId, "userId", userId), () -> {
            Map<String, Object> order = MOCK_ORDERS.get(orderId);
            if (order == null) {
                return json(Map.of("ok", false, "message", "订单不存在"));
            }
            if (!userId.equals(order.get("userId"))) {
                return json(Map.of("ok", false, "message", "无权查看该订单"));
            }
            return json(Map.of("ok", true, "order", order));
        });
    }

    @Tool(description = "取消当前用户名下待发货订单；执行前必须已获用户确认")
    public String cancelOrder(
            @ToolParam(description = "订单号") String orderId,
            @ToolParam(description = "当前用户 ID") String userId,
            @ToolParam(description = "取消原因") String reason
    ) {
        return execute("cancel_order", Map.of("orderId", orderId, "userId", userId, "reason", reason), () -> {
            Map<String, Object> order = MOCK_ORDERS.get(orderId);
            if (order == null) {
                return json(Map.of("ok", false, "message", "订单不存在"));
            }
            if (!userId.equals(order.get("userId"))) {
                return json(Map.of("ok", false, "message", "无权取消该订单"));
            }
            if (!Boolean.TRUE.equals(order.get("cancellable"))) {
                return json(Map.of("ok", false, "message", "订单当前状态不可取消：" + order.get("status")));
            }
            Map<String, Object> updated = new LinkedHashMap<>(order);
            updated.put("status", "已取消");
            updated.put("cancellable", false);
            updated.put("cancelReason", reason);
            MOCK_ORDERS.put(orderId, updated);
            return json(Map.of("ok", true, "message", "订单已取消", "order", updated));
        });
    }

    @Tool(description = "根据运单号查询物流轨迹")
    public String queryLogistics(@ToolParam(description = "运单号") String trackingNo) {
        return execute("query_logistics", Map.of("trackingNo", trackingNo), () -> {
            List<Map<String, String>> traces = List.of(
                    Map.of("time", "2026-07-29 14:00", "status", "快件已揽收", "location", "深圳"),
                    Map.of("time", "2026-07-30 09:30", "status", "运输中", "location", "广州转运中心"),
                    Map.of("time", "2026-07-30 18:00", "status", "派送中", "location", "北京")
            );
            return json(Map.of("ok", true, "trackingNo", trackingNo, "carrier", "顺丰", "traces", traces));
        });
    }

    @Tool(description = "创建售后工单")
    public String createTicket(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "问题分类：破损少件/物流异常/退换货/其他") String category,
            @ToolParam(description = "问题描述") String description,
            @ToolParam(description = "优先级 P1/P2/P3") String priority
    ) {
        String p = priority != null && !priority.isBlank() ? priority : "P3";
        return execute("create_ticket", Map.of(
                "userId", userId, "category", category, "description", description, "priority", p
        ), () -> {
            String ticketId = "TK" + System.currentTimeMillis();
            Map<String, Object> ticket = new LinkedHashMap<>();
            ticket.put("ticketId", ticketId);
            ticket.put("userId", userId);
            ticket.put("category", category);
            ticket.put("description", description);
            ticket.put("priority", p);
            ticket.put("status", "open");
            ticket.put("createdAt", Instant.now().toString());
            MOCK_TICKETS.add(ticket);
            return json(Map.of("ok", true, "ticketId", ticketId, "message", "工单已创建"));
        });
    }

    @Tool(description = "转接人工客服")
    public String escalateHuman(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "转人工原因") String reason
    ) {
        return execute("escalate_human", Map.of("userId", userId, "reason", reason), () ->
                json(Map.of(
                        "ok", true,
                        "queuePosition", 3,
                        "estimatedWaitMinutes", 5,
                        "message", "已为您转接人工客服，请稍候"
                ))
        );
    }

    private String execute(String toolName, Map<String, Object> args, ToolAction action) {
        long start = System.currentTimeMillis();
        String result;
        boolean success = true;
        try {
            result = action.run();
        } catch (Exception e) {
            success = false;
            result = json(Map.of("ok", false, "message", e.getMessage()));
        }
        long latency = System.currentTimeMillis() - start;
        audit(toolName, args, result, latency, success);
        return result;
    }

    private void audit(String toolName, Map<String, Object> args, String result, long latencyMs, boolean success) {
        try {
            CsToolInvocation inv = new CsToolInvocation();
            inv.setSessionId(SESSION_CONTEXT.get());
            inv.setToolName(toolName);
            inv.setArgsJson(objectMapper.writeValueAsString(args));
            inv.setResultJson(result);
            inv.setSource("local");
            inv.setLatencyMs(latencyMs);
            inv.setCreatedAt(Instant.now());
            auditRepo.save(inv);
        } catch (Exception ignored) {
        }
    }

    private String json(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"message\":\"serialization error\"}";
        }
    }

    @FunctionalInterface
    private interface ToolAction {
        String run() throws Exception;
    }
}
