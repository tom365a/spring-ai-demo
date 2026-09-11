package com.demo.cs.infrastructure.tools;

import com.demo.cs.application.business.OrderTicketService;
import com.demo.cs.application.support.HumanSupportService;
import com.demo.cs.domain.CsToolInvocation;
import com.demo.cs.infrastructure.persistence.CsToolInvocationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OrderTicketTools {

    private final CsToolInvocationRepository auditRepo;
    private final ObjectMapper objectMapper;

    private final OrderTicketService business;
    private final HumanSupportService support;

    public OrderTicketTools(CsToolInvocationRepository auditRepo, ObjectMapper objectMapper,
                            OrderTicketService business, HumanSupportService support) {
        this.auditRepo = auditRepo;
        this.objectMapper = objectMapper;
        this.business = business;
        this.support = support;
    }

    public void setSessionId(String sessionId) {
        ToolSessionContext.set(sessionId);
    }

    public void clearSessionId() {
        ToolSessionContext.clear();
    }

    public List<Map<String, Object>> tickets() {
        return business.tickets();
    }

    @Tool(description = "根据订单号查询当前用户的订单状态与基本信息")
    public String queryOrder(
            @ToolParam(description = "订单号，如 ORD20260730001") String orderId,
            @ToolParam(description = "当前用户 ID") String userId
    ) {
        return execute("query_order", Map.of("orderId", orderId, "userId", userId),
                () -> json(business.queryOrder(orderId, userId)));
    }

    @Tool(description = "取消当前用户名下待发货订单；执行前必须已获用户确认")
    public String cancelOrder(
            @ToolParam(description = "订单号") String orderId,
            @ToolParam(description = "当前用户 ID") String userId,
            @ToolParam(description = "取消原因") String reason
    ) {
        return execute("cancel_order", Map.of("orderId", orderId, "userId", userId, "reason", reason),
                () -> json(business.cancelOrder(orderId, userId, reason)));
    }

    @Tool(description = "根据运单号查询物流轨迹")
    public String queryLogistics(@ToolParam(description = "运单号") String trackingNo) {
        return execute("query_logistics", Map.of("trackingNo", trackingNo),
                () -> json(business.queryLogistics(trackingNo)));
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
        ), () -> json(business.createTicket(userId, category, description, p, null)));
    }

    @Tool(description = "转接人工客服")
    public String escalateHuman(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "转人工原因") String reason
    ) {
        String sessionId = ToolSessionContext.get();
        return execute("escalate_human", Map.of("userId", userId, "reason", reason,
                "sessionId", sessionId == null ? "-" : sessionId), () -> {
            if (sessionId == null || sessionId.isBlank()) {
                return json(Map.of("ok", false, "message", "当前没有可转接的会话，请让用户重新发起对话"));
            }
            // 真的把会话挂进坐席队列。不落队列的「转人工」等于没转：下一句仍然会被模型接走。
            Map<String, Object> view = support.transfer(sessionId, userId);
            // 只把客户该听到的字段交给模型：会话 ID 和状态枚举一旦回传，模型就会原样念给客户。
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", view.get("message"));
            if (view.get("queuePosition") != null) out.put("queuePosition", view.get("queuePosition"));
            if (view.get("operatorName") != null) out.put("operatorName", view.get("operatorName"));
            return json(out);
        });
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
        if (com.demo.cs.application.resources.ToolExecutionGateway.managedExecution()) return;
        try {
            CsToolInvocation inv = new CsToolInvocation();
            inv.setSessionId(ToolSessionContext.get());
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
