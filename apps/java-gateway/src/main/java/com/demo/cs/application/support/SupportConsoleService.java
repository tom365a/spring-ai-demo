package com.demo.cs.application.support;

import com.demo.cs.application.session.ConversationMonitor;
import com.demo.cs.application.session.SessionService;
import com.demo.cs.domain.CsMessage;
import com.demo.cs.domain.SupportAssignment;
import com.demo.cs.infrastructure.persistence.CsSessionRepository;
import com.demo.cs.infrastructure.persistence.SupportAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 真人坐席控制台。转人工后的会话在这里排队，由真人认领、打字回复、结束接管。
 * 回复直接落进会话消息流，客户端轮询取回——没有任何模型参与。
 */
@Service
public class SupportConsoleService {

    private final SupportAssignmentRepository assignments;
    private final CsSessionRepository sessionRepo;
    private final SessionService sessions;
    private final ConversationMonitor monitor;

    public SupportConsoleService(SupportAssignmentRepository assignments, CsSessionRepository sessionRepo,
                                 SessionService sessions, ConversationMonitor monitor) {
        this.assignments = assignments;
        this.sessionRepo = sessionRepo;
        this.sessions = sessions;
        this.monitor = monitor;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> queue() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (SupportAssignment a : assignments.findByStatusInOrderByAssignedAtAsc(
                List.of(SupportAssignment.WAITING, SupportAssignment.ACTIVE))) {
            var session = sessionRepo.findById(a.sessionId).orElse(null);
            if (session == null) continue;
            List<CsMessage> history = sessions.allMessages(a.sessionId);
            CsMessage lastUser = null;
            for (CsMessage m : history) if ("user".equals(m.getRole())) lastUser = m;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sessionId", a.sessionId);
            row.put("userId", session.getUserId());
            row.put("status", a.status);
            row.put("mode", a.mode);
            row.put("operatorName", a.operatorName);
            row.put("assignedAt", a.assignedAt);
            row.put("claimedAt", a.claimedAt);
            row.put("lastCustomerAt", a.lastCustomerAt);
            row.put("lastOperatorAt", a.lastOperatorAt);
            row.put("waitingReply", a.lastCustomerAt != null
                    && (a.lastOperatorAt == null || a.lastOperatorAt.isBefore(a.lastCustomerAt)));
            row.put("lastCustomerText", lastUser == null ? null : lastUser.getContent());
            row.put("messageCount", history.size());
            items.add(row);
        }
        return Map.of("items", items, "waiting",
                items.stream().filter(i -> SupportAssignment.WAITING.equals(i.get("status"))).count());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> conversation(String sessionId) {
        SupportAssignment a = assignments.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("该会话未转人工"));
        var session = sessionRepo.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("userId", session.getUserId());
        out.put("status", a.status);
        out.put("mode", a.mode);
        out.put("operatorName", a.operatorName);
        out.put("sessionStatus", session.getStatus());
        out.put("messages", sessions.allMessages(sessionId).stream().map(SupportConsoleService::message).toList());
        return out;
    }

    /** 认领在悲观锁下完成：两个坐席同时点，只有一个能拿到。 */
    @Transactional
    public Map<String, Object> claim(String sessionId, String operatorId, String operatorName) {
        if (operatorId == null || operatorId.isBlank()) throw new IllegalArgumentException("缺少坐席标识");
        SupportAssignment a = assignments.lockById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("该会话未转人工"));
        if (SupportAssignment.CLOSED.equals(a.status)) throw new IllegalStateException("该会话的人工接管已结束");
        if (SupportAssignment.ACTIVE.equals(a.status) && !operatorId.equals(a.operatorId))
            throw new IllegalStateException("会话已被坐席 " + a.operatorName + " 认领");

        a.status = SupportAssignment.ACTIVE;
        a.operatorId = operatorId;
        a.operatorName = operatorName == null || operatorName.isBlank() ? operatorId : operatorName;
        if (a.claimedAt == null) a.claimedAt = Instant.now();
        assignments.save(a);

        sessionRepo.findById(sessionId).ifPresent(s -> { s.setLastAgent(a.operatorName); sessionRepo.save(s); });
        monitor.event(sessionId, null, "HUMAN_CLAIMED", a.operatorName, Map.of("mode", a.mode, "operatorId", operatorId));
        sessions.appendMessage(sessionId, "assistant", "坐席已接入，请继续描述您的问题。",
                a.operatorName, null, null, List.of());
        return conversation(sessionId);
    }

    /** 坐席回复：真人输入的文本原样进入会话，不经过任何模型。 */
    @Transactional
    public Map<String, Object> reply(String sessionId, String operatorId, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("回复内容不能为空");
        if (text.length() > 4000) throw new IllegalArgumentException("单条回复最多 4000 字");
        SupportAssignment a = assignments.lockById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("该会话未转人工"));
        if (!SupportAssignment.ACTIVE.equals(a.status)) throw new IllegalStateException("请先认领该会话");
        if (!Objects.equals(a.operatorId, operatorId)) throw new SecurityException("该会话由其他坐席接管");
        var session = sessionRepo.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if ("closed".equals(session.getStatus())) throw new IllegalStateException("会话已结束");

        sessions.appendMessage(sessionId, "assistant", text, a.operatorName, null, null, List.of());
        a.lastOperatorAt = Instant.now();
        assignments.save(a);
        monitor.event(sessionId, null, "HUMAN_OPERATOR_REPLY", a.operatorName, Map.of("mode", a.mode, "chars", text.length()));
        return conversation(sessionId);
    }

    @Transactional
    public Map<String, Object> close(String sessionId, String operatorId, String note) {
        SupportAssignment a = assignments.lockById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("该会话未转人工"));
        if (a.operatorId != null && !Objects.equals(a.operatorId, operatorId))
            throw new SecurityException("该会话由其他坐席接管");
        a.status = SupportAssignment.CLOSED;
        a.closedAt = Instant.now();
        assignments.save(a);
        monitor.event(sessionId, null, "HUMAN_RELEASED", a.operatorName,
                Map.of("mode", a.mode, "note", Objects.requireNonNullElse(note, "")));
        sessions.appendMessage(sessionId, "assistant",
                "人工客服已结束本次接管，后续问题将由智能客服继续为您服务。", a.operatorName, null, null, List.of());
        return Map.of("sessionId", sessionId, "status", a.status);
    }

    /** 供客户端轮询：把会话消息整体返回，客户端按 id 渲染未见过的条目。 */
    @Transactional(readOnly = true)
    public Map<String, Object> customerView(String sessionId, String userId) {
        var session = sessionRepo.findById(sessionId).orElse(null);
        if (session == null || !session.getUserId().equals(userId)) throw new SecurityException("会话不可访问");
        SupportAssignment a = assignments.findById(sessionId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assigned", a != null && !SupportAssignment.CLOSED.equals(a.status));
        out.put("status", a == null ? null : a.status);
        out.put("mode", a == null ? null : a.mode);
        out.put("operatorName", a == null ? null : a.operatorName);
        out.put("messages", sessions.allMessages(sessionId).stream().map(SupportConsoleService::message).toList());
        return out;
    }

    private static Map<String, Object> message(CsMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("role", m.getRole());
        out.put("content", m.getContent());
        out.put("agentName", m.getAgentName());
        out.put("createdAt", m.getCreatedAt());
        return out;
    }
}
