package com.demo.cs.infrastructure.tools;

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

/**
 * Mock tools for the defect-compensation (瑕疵补偿) sub-agent demo.
 */
@Component
public class DefectCompensationTools {

    private static final ThreadLocal<String> SESSION_CONTEXT = new ThreadLocal<>();

    private final CsToolInvocationRepository auditRepo;
    private final ObjectMapper objectMapper;

    public DefectCompensationTools(CsToolInvocationRepository auditRepo, ObjectMapper objectMapper) {
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

    @Tool(description = "引导用户上传瑕疵图片；也可校验已上传图片是否为瑕疵图")
    public String askForDefectImage(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "是否已有图片：true/false") String hasImage,
            @ToolParam(description = "可选：图片 ID 或描述") String imageRef
    ) {
        boolean has = "true".equalsIgnoreCase(hasImage) || (imageRef != null && !imageRef.isBlank());
        return execute("ask_for_defect_image", Map.of(
                "userId", nullToEmpty(userId),
                "hasImage", has,
                "imageRef", nullToEmpty(imageRef)
        ), () -> {
            if (!has) {
                return json(Map.of(
                        "ok", true,
                        "needImage", true,
                        "message", "请上传一张能清晰看到商品瑕疵（破损/污渍/做工问题）的图片，便于为您申请补偿。"
                ));
            }
            return json(Map.of(
                    "ok", true,
                    "needImage", false,
                    "isDefectImage", true,
                    "imageRef", nullToEmpty(imageRef),
                    "message", "已识别为瑕疵相关图片，可继续提供订单/子订单信息。"
            ));
        });
    }

    @Tool(description = "根据瑕疵图片查询关联订单，或校验用户提供的订单/子订单")
    public String askForDefectOrder(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "可选订单号") String orderId,
            @ToolParam(description = "可选图片引用") String imageRef
    ) {
        return execute("ask_for_defect_order", Map.of(
                "userId", nullToEmpty(userId),
                "orderId", nullToEmpty(orderId),
                "imageRef", nullToEmpty(imageRef)
        ), () -> {
            if (orderId != null && !orderId.isBlank()) {
                String sub = orderId.startsWith("SUB") ? orderId : "SUB-" + orderId + "-01";
                return json(Map.of(
                        "ok", true,
                        "found", true,
                        "orderId", orderId,
                        "subOrderId", sub,
                        "needConfirm", true,
                        "message", "已定位单据 " + sub + "，请确认是否使用该子订单申请瑕疵补偿。"
                ));
            }
            if (imageRef != null && !imageRef.isBlank()) {
                return json(Map.of(
                        "ok", true,
                        "found", true,
                        "orderId", "ORD20260730002",
                        "subOrderId", "SUB-ORD20260730002-01",
                        "needConfirm", true,
                        "message", "根据瑕疵图片匹配到子订单 SUB-ORD20260730002-01（手机壳），请确认是否正确。"
                ));
            }
            return json(Map.of(
                    "ok", true,
                    "found", false,
                    "message", "未查询到瑕疵图片关联订单，请继续提供子订单号。"
            ));
        });
    }

    @Tool(description = "引导用户提供子订单号")
    public String askForSubOrder(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "提示文案，可空") String hint
    ) {
        return execute("ask_for_sub_order", Map.of(
                "userId", nullToEmpty(userId),
                "hint", nullToEmpty(hint)
        ), () -> json(Map.of(
                "ok", true,
                "message", "请提供子订单号（例如 SUB-ORD20260730002-01），以便继续瑕疵补偿流程。"
        )));
    }

    @Tool(description = "查询瑕疵补偿路由信息：售后状态、是否可申请、用户价值等")
    public String xcbcRoute(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "子订单号") String subOrderId
    ) {
        return execute("xcbc_route", Map.of(
                "userId", nullToEmpty(userId),
                "subOrderId", nullToEmpty(subOrderId)
        ), () -> json(Map.of(
                "ok", true,
                "subOrderId", nullToEmpty(subOrderId),
                "hasAfterSalePendingConfirm", false,
                "withinAfterSaleWindow", true,
                "alreadyApplied", false,
                "hasDefectImage", true,
                "suggestedNext", "user_value_router",
                "message", "已查询到子订单补偿路由信息，建议先判断用户价值。"
        )));
    }

    @Tool(description = "引导用户确认或拒绝待确认的售后单")
    public String confirmAsOrderCallback(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "售后单号") String afterSaleId
    ) {
        return execute("confirm_as_order_callback", Map.of(
                "userId", nullToEmpty(userId),
                "afterSaleId", nullToEmpty(afterSaleId)
        ), () -> json(Map.of(
                "ok", true,
                "afterSaleId", nullToEmpty(afterSaleId),
                "message", "您有一笔待确认的售后单（" + nullToEmpty(afterSaleId) + "）。"
        )));
    }

    @Tool(description = "用户确认售后单后的回复回调")
    public String aggreAsOrderCallback(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "售后单号") String afterSaleId
    ) {
        return execute("aggre_as_order_callback", Map.of(
                "userId", nullToEmpty(userId),
                "afterSaleId", nullToEmpty(afterSaleId)
        ), () -> json(Map.of(
                "ok", true,
                "message", "已确认售后单 " + nullToEmpty(afterSaleId) + "，将继续为您处理瑕疵补偿。"
        )));
    }

    @Tool(description = "用户拒绝售后单后的回复回调")
    public String rejectAsOrderCallback(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "售后单号") String afterSaleId
    ) {
        return execute("reject_as_order_callback", Map.of(
                "userId", nullToEmpty(userId),
                "afterSaleId", nullToEmpty(afterSaleId)
        ), () -> json(Map.of(
                "ok", true,
                "message", "已拒绝售后单 " + nullToEmpty(afterSaleId) + "。如仍需瑕疵补偿，可重新发起申请。"
        )));
    }

    @Tool(description = "判断用户是否为高价值用户")
    public String userValueRouter(
            @ToolParam(description = "用户 ID") String userId
    ) {
        boolean high = userId != null && (
                userId.equals("u_001")
                        || userId.endsWith("_u_001")
                        || userId.contains("u_001")
                        || userId.startsWith("vip")
        );
        return execute("user_value_router", Map.of("userId", nullToEmpty(userId)), () -> json(Map.of(
                "ok", true,
                "userId", nullToEmpty(userId),
                "highValue", high,
                "suggestedNext", high ? "xcbc_sub_order_route" : "low_user_value_callback",
                "message", high ? "识别为高价值用户，可继续申请瑕疵补偿。" : "当前非高价值用户，将走标准说明话术。"
        )));
    }

    @Tool(description = "对非高价值用户的标准回复回调")
    public String lowUserValueCallback(
            @ToolParam(description = "用户 ID") String userId
    ) {
        return execute("low_user_value_callback", Map.of("userId", nullToEmpty(userId)), () -> json(Map.of(
                "ok", true,
                "message", "抱歉，当前账号暂不支持在线瑕疵补偿自动申请。您可转人工或提交普通售后工单，我们会尽快协助处理。"
        )));
    }

    @Tool(description = "查询已申请瑕疵补偿的处理进度")
    public String xcbcProcessCallback(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "子订单号，可空") String subOrderId
    ) {
        return execute("xcbc_process_callback", Map.of(
                "userId", nullToEmpty(userId),
                "subOrderId", nullToEmpty(subOrderId)
        ), () -> json(Map.of(
                "ok", true,
                "status", "审核中",
                "progress", "已收图，补偿方案评估中",
                "etaHours", 24,
                "message", "您的瑕疵补偿申请正在审核中，预计 24 小时内给出方案。"
        )));
    }

    @Tool(description = "帮助用户基于子订单申请瑕疵补偿")
    public String xcbcSubOrderRoute(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "子订单号") String subOrderId,
            @ToolParam(description = "瑕疵描述") String defectDesc
    ) {
        String applyId = "XCBC" + System.currentTimeMillis();
        return execute("xcbc_sub_order_route", Map.of(
                "userId", nullToEmpty(userId),
                "subOrderId", nullToEmpty(subOrderId),
                "defectDesc", nullToEmpty(defectDesc)
        ), () -> json(Map.of(
                "ok", true,
                "applyId", applyId,
                "subOrderId", nullToEmpty(subOrderId),
                "compensationType", "部分退款/优惠券",
                "message", "已为子订单 " + nullToEmpty(subOrderId) + " 提交瑕疵补偿申请（" + applyId + "），请留意审核结果。"
        )));
    }

    @Tool(description = "查询售后是否申请成功")
    public String applyAfterSaleSuccess(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "子订单号或售后单号") String refId
    ) {
        return execute("apply_after_sale_success", Map.of(
                "userId", nullToEmpty(userId),
                "refId", nullToEmpty(refId)
        ), () -> json(Map.of(
                "ok", true,
                "applied", true,
                "refId", nullToEmpty(refId),
                "status", "已提交",
                "message", "售后申请已成功提交，单号关联 " + nullToEmpty(refId) + "。"
        )));
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @FunctionalInterface
    private interface ToolAction {
        String run() throws Exception;
    }
}
