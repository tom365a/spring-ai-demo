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

    public record ToolEntry(
            String code,
            String name,
            String description,
            SideEffect sideEffect,
            String ownerDomain,
            String methodName
    ) {}

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
