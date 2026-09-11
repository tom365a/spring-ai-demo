package com.demo.cs;

import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.api.dto.ApiDtos.ChatRequest;
import com.demo.cs.application.business.OrderTicketService;
import com.demo.cs.application.session.ConversationMonitor;
import com.demo.cs.application.session.SessionService;
import com.demo.cs.application.support.HumanSupportService;
import com.demo.cs.application.support.SupportImAdapter;
import com.demo.cs.config.AppProperties;
import com.demo.cs.domain.SupportAssignment;
import com.demo.cs.infrastructure.persistence.CsSessionRepository;
import com.demo.cs.infrastructure.persistence.SupportAssignmentRepository;
import com.demo.cs.infrastructure.tools.OrderTicketTools;
import com.demo.cs.infrastructure.tools.ToolSessionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * escalate_human 必须真的把会话挂进坐席队列，并且只报真实数据。
 * 旧实现返回写死的 queuePosition=3 / estimatedWaitMinutes=5 且不落库，
 * 结果是「已转人工」之后下一句仍被模型接走，排队位次也是编的。
 */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "app.support.mode=HUMAN"})
@Import({OrderTicketTools.class, OrderTicketService.class, HumanSupportService.class,
         SessionService.class, ConversationMonitor.class, EscalateHumanToolTest.Beans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EscalateHumanToolTest {

    @TestConfiguration
    static class Beans {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean AppProperties props() { return new AppProperties(null, null, null, 10, .55, null, null, null, null); }
        @Bean DefinitionRegistry agents() { return mock(DefinitionRegistry.class); }
        @Bean SupportImAdapter adapter() { return mock(SupportImAdapter.class); }
    }

    @Autowired OrderTicketTools tools;
    @Autowired HumanSupportService support;
    @Autowired SessionService sessions;
    @Autowired SupportAssignmentRepository assignments;
    @Autowired CsSessionRepository sessionRepo;
    @Autowired ObjectMapper json;

    @AfterEach
    void clearContext() {
        ToolSessionContext.clear();
    }

    private Map<String, Object> escalate(String sessionId, String userId) throws Exception {
        ToolSessionContext.set(sessionId);
        try {
            return json.readValue(tools.escalateHuman(userId, "客户要求人工"), Map.class);
        } finally {
            ToolSessionContext.clear();
        }
    }

    @Test
    void toolActuallyQueuesTheSessionSoTheModelStopsAnswering() throws Exception {
        var s = sessions.createSession("u_001", "web");
        sessions.appendMessage(s.getId(), "user", "转人工", null, null, null, List.of());

        assertThat(support.assigned(s.getId())).isFalse();
        Map<String, Object> out = escalate(s.getId(), "u_001");

        assertThat(out).containsEntry("ok", true);
        assertThat(String.valueOf(out.get("message"))).contains("已转人工");
        // 会话 ID 和状态枚举不回传给模型，否则会被原样念给客户。
        assertThat(out).doesNotContainKeys("sessionId", "status", "mode");
        assertThat(assignments.findById(s.getId()).orElseThrow().status).isEqualTo(SupportAssignment.WAITING);
        // 会话真的进了队列：这正是 AgentOrchestrator 用来停掉模型的开关。
        assertThat(support.assigned(s.getId())).isTrue();
        assertThat(assignments.findById(s.getId())).isPresent();

        var next = support.chat(new ChatRequest(s.getId(), "u_001", "你好", List.of(), null, null, null, null, null));
        assertThat(next.answer()).contains("正在为您接入坐席");
        assertThat(next.diagnostics()).containsEntry("awaitingOperator", true);
    }

    @Test
    void queuePositionIsCountedNotInvented() throws Exception {
        assignments.deleteAll();   // 位次是全局队列的序号，先清干净再断言绝对值
        var first = sessions.createSession("u_001", "web");
        var second = sessions.createSession("u_002", "web");

        assertThat(escalate(first.getId(), "u_001")).containsEntry("queuePosition", 1);
        assertThat(escalate(second.getId(), "u_002")).containsEntry("queuePosition", 2);
        // 没有等待时长这个字段——没有数据支撑就不要报数字。
        assertThat(escalate(second.getId(), "u_002")).doesNotContainKey("estimatedWaitMinutes");
    }

    @Test
    void queuedCustomerIsNotTimedOutWhileWaitingForAnOperator() throws Exception {
        var s = sessions.createSession("u_001", "web");
        escalate(s.getId(), "u_001");

        // 排队等真人的客户本来就不打字。把最后输入时间推到闲置阈值之外，模拟等了十分钟。
        var row = sessionRepo.findById(s.getId()).orElseThrow();
        row.setLastInputAt(Instant.now().minusSeconds(600));
        sessionRepo.save(row);

        sessions.expireIdleSessions();
        assertThat(sessionRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("active");

        // 坐席结束接管后，会话重新受闲置规则约束。
        var a = assignments.findById(s.getId()).orElseThrow();
        a.status = SupportAssignment.CLOSED;
        assignments.save(a);
        sessions.expireIdleSessions();
        assertThat(sessionRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("closed");
    }

    @Test
    void abandonedHandoffEventuallyLeavesTheQueueInsteadOfBlockingItForever() throws Exception {
        var s = sessions.createSession("u_001", "web");
        escalate(s.getId(), "u_001");

        // 客户关掉页面就再也没回来。把全部活跃时间戳推到放弃阈值之外。
        var stale = Instant.now().minusSeconds(24 * 3600);
        var row = sessionRepo.findById(s.getId()).orElseThrow();
        row.setLastInputAt(stale);
        sessionRepo.save(row);
        var a = assignments.findById(s.getId()).orElseThrow();
        a.assignedAt = stale; a.lastCustomerAt = stale; a.lastOperatorAt = null;
        assignments.save(a);

        sessions.expireIdleSessions();

        // 分配被关掉、退出坐席队列，会话也随之结束；否则客户下次转人工会排在自己这条影子会话后面。
        assertThat(assignments.findById(s.getId()).orElseThrow().status).isEqualTo(SupportAssignment.CLOSED);
        assertThat(support.assigned(s.getId())).isFalse();
        assertThat(sessionRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("closed");
    }

    @Test
    void missingSessionIsRejectedAsBusinessFailureNotAnException() throws Exception {
        ToolSessionContext.clear();
        Map<String, Object> out = json.readValue(tools.escalateHuman("u_001", "无会话"), Map.class);
        assertThat(out).containsEntry("ok", false);
        assertThat(String.valueOf(out.get("message"))).contains("重新发起对话");
    }

    @Test
    void wrongOwnerIsRejectedAsBusinessFailureNotAnException() throws Exception {
        var s = sessions.createSession("u_001", "web");
        Map<String, Object> out = escalate(s.getId(), "u_999");
        assertThat(out).containsEntry("ok", false);
        assertThat(support.assigned(s.getId())).isFalse();
    }
}
