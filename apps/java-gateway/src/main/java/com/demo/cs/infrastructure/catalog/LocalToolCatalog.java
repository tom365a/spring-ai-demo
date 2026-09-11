package com.demo.cs.infrastructure.catalog;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LocalToolCatalog {

    public enum SideEffect { READ, WRITE }

    /**
     * 二选一的展现方式。确认卡片只有两个按钮，把业务上的「同意/拒绝」映射上去：
     * 确认 = 执行本工具，取消 = 改为执行 {@code rejectTool}。
     * 不填就是普通的「确认执行/取消操作」。
     */
    public record Choice(String prompt, String confirmLabel, String cancelLabel, String rejectTool) {}

    public record ToolEntry(
            String code,
            String name,
            String description,
            SideEffect sideEffect,
            String ownerDomain,
            String methodName,
            Choice choice
    ) {
        public ToolEntry(String code, String name, String description, SideEffect sideEffect,
                         String ownerDomain, String methodName) {
            this(code, name, description, sideEffect, ownerDomain, methodName, null);
        }
    }

    private final Map<String, ToolEntry> entries = new LinkedHashMap<>();

    public LocalToolCatalog() {
        register(new ToolEntry("query_order", "查询订单",
                "根据订单号查询当前用户的订单状态与基本信息", SideEffect.READ, "order", "queryOrder"));
        register(new ToolEntry("query_logistics", "查询物流",
                "根据运单号查询物流轨迹", SideEffect.READ, "order", "queryLogistics"));
        register(new ToolEntry("cancel_order", "取消订单",
                "取消当前用户名下待发货订单；执行前必须已获用户确认", SideEffect.WRITE, "order", "cancelOrder"));
        register(new ToolEntry("create_ticket", "创建工单",
                "创建售后工单", SideEffect.WRITE, "ticket", "createTicket"));
        register(new ToolEntry("escalate_human", "转人工",
                "转接人工客服", SideEffect.WRITE, "ticket", "escalateHuman"));

        // 瑕疵补偿助手
        register(new ToolEntry("ask_for_defect_image", "引导/校验瑕疵图片",
                "引导用户上传瑕疵图片，或校验图片是否为瑕疵图", SideEffect.READ, "defect", "askForDefectImage"));
        register(new ToolEntry("ask_for_defect_order", "查询/校验瑕疵订单",
                "根据图片或订单号查询瑕疵关联单据", SideEffect.READ, "defect", "askForDefectOrder"));
        register(new ToolEntry("ask_for_sub_order", "引导提供子订单",
                "引导用户提供子订单号", SideEffect.READ, "defect", "askForSubOrder"));
        register(new ToolEntry("xcbc_route", "瑕疵补偿路由查询",
                "查询瑕疵补偿所需路由信息", SideEffect.READ, "defect", "xcbcRoute"));
        // 它只是把待确认售后单读出来告知用户，不改任何业务状态。标成 WRITE 会让用户先点一次
        // 「确认执行」才能被问一个问题，白多一道卡片。
        register(new ToolEntry("confirm_as_order_callback", "售后待确认引导",
                "查询并告知待确认的售后单", SideEffect.READ, "defect", "confirmAsOrderCallback"));
        // 「同意 / 拒绝」是一次二选一，不是「要不要执行」。挂到确认卡片的两个按钮上：
        // 点同意就执行本工具，点拒绝就改为执行 reject_as_order_callback——用户不需要打字。
        register(new ToolEntry("aggre_as_order_callback", "确认售后单",
                "用户确认售后单后的回复", SideEffect.WRITE, "defect", "aggreAsOrderCallback",
                new Choice("请确认这笔待处理的售后单", "同意", "拒绝", "reject_as_order_callback")));
        register(new ToolEntry("reject_as_order_callback", "拒绝售后回调",
                "用户拒绝售后单后的回复", SideEffect.WRITE, "defect", "rejectAsOrderCallback"));
        register(new ToolEntry("user_value_router", "用户价值判断",
                "判断是否高价值用户", SideEffect.READ, "defect", "userValueRouter"));
        register(new ToolEntry("low_user_value_callback", "低价值用户话术",
                "非高价值用户的标准回复", SideEffect.READ, "defect", "lowUserValueCallback"));
        register(new ToolEntry("xcbc_process_callback", "补偿进度查询",
                "查询已申请瑕疵补偿进度", SideEffect.READ, "defect", "xcbcProcessCallback"));
        register(new ToolEntry("xcbc_sub_order_route", "提交瑕疵补偿申请",
                "基于子订单申请瑕疵补偿", SideEffect.WRITE, "defect", "xcbcSubOrderRoute"));
        register(new ToolEntry("apply_after_sale_success", "售后申请结果查询",
                "查询售后是否申请成功", SideEffect.READ, "defect", "applyAfterSaleSuccess"));
    }

    private void register(ToolEntry entry) {
        entries.put(entry.code(), entry);
    }

    public List<ToolEntry> list() {
        return List.copyOf(entries.values());
    }

    public Optional<ToolEntry> get(String code) {
        return Optional.ofNullable(entries.get(code));
    }

    public boolean isWrite(String code) {
        return get(code).map(e -> e.sideEffect() == SideEffect.WRITE).orElse(false);
    }

    public boolean containsAll(Collection<String> codes) {
        if (codes == null) return true;
        for (String code : codes) {
            if (!entries.containsKey(code)) return false;
        }
        return true;
    }
}
