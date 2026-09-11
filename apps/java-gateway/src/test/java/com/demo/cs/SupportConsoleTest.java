package com.demo.cs;

import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.api.dto.ApiDtos.ChatRequest;
import com.demo.cs.application.session.ConversationMonitor;
import com.demo.cs.application.session.SessionService;
import com.demo.cs.application.support.HumanSupportService;
import com.demo.cs.application.support.SupportConsoleService;
import com.demo.cs.application.support.SupportImAdapter;
import com.demo.cs.config.AppProperties;
import com.demo.cs.domain.SupportAssignment;
import com.demo.cs.infrastructure.persistence.SupportAssignmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真人坐席接管：会话排队、坐席认领、真人回复进入客户消息流，全程无模型参与。 */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "app.support.mode=HUMAN"})
@Import({HumanSupportService.class, SupportConsoleService.class, SessionService.class,
         ConversationMonitor.class, SupportConsoleTest.Beans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportConsoleTest {

    @TestConfiguration
    static class Beans {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean AppProperties props() { return new AppProperties(null, null, null, 10, .55, null, null, null, null); }
        @Bean DefinitionRegistry agents() { return mock(DefinitionRegistry.class); }
        @Bean SupportImAdapter adapter() { return mock(SupportImAdapter.class); }
    }

    @Autowired HumanSupportService support;
    @Autowired SupportConsoleService console;
    @Autowired SessionService sessions;
    @Autowired SupportAssignmentRepository assignments;
    @Autowired SupportImAdapter adapter;

    @Test
    void customerMessagesQueueForAnOperatorAndNoReplyIsFabricated() {
        var s = sessions.createSession("u_real", "web");
        sessions.appendMessage(s.getId(), "user", "我要退款", null, null, null, List.of());

        Map<String, Object> handoff = support.transfer(s.getId(), "u_real");
        assertThat(handoff).containsEntry("mode", "HUMAN").containsEntry("status", SupportAssignment.WAITING);

        var outcome = support.chat(new ChatRequest(s.getId(), "u_real", "在吗", List.of(), null, null, null, null, null));
        assertThat(outcome.diagnostics()).containsEntry("awaitingOperator", true);
        assertThat(outcome.answer()).contains("已送达");
        // 关键：真人模式下不得调用任何模型/适配器伪造回复
        verifyNoInteractions(adapter);

        // 队列是全局的，只断言本会话这一行（用例之间共用同一个库）
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) console.queue().get("items");
        var mine = rows.stream().filter(r -> s.getId().equals(r.get("sessionId"))).findFirst().orElseThrow();
        assertThat(mine).containsEntry("status", SupportAssignment.WAITING)
                .containsEntry("waitingReply", true)
                .containsEntry("lastCustomerText", "在吗")
                .containsEntry("operatorName", null);
        sessions.closeSession(s.getId());
    }

    @Test
    void operatorReplyReachesTheCustomerVerbatim() {
        var s = sessions.createSession("u_reply", "web");
        support.transfer(s.getId(), "u_reply");
        support.chat(new ChatRequest(s.getId(), "u_reply", "订单没收到", List.of(), null, null, null, null, null));

        console.claim(s.getId(), "seat_a", "客服小王");
        assertThat(assignments.findById(s.getId()).orElseThrow().status).isEqualTo(SupportAssignment.ACTIVE);

        console.reply(s.getId(), "seat_a", "您好，已帮您查询，预计明天送达。");

        Map<String, Object> view = console.customerView(s.getId(), "u_reply");
        assertThat(view).containsEntry("assigned", true).containsEntry("operatorName", "客服小王");
        @SuppressWarnings("unchecked") var messages = (List<Map<String, Object>>) view.get("messages");
        assertThat(messages).anyMatch(m -> "您好，已帮您查询，预计明天送达。".equals(m.get("content"))
                && "客服小王".equals(m.get("agentName")));
        // 真人文本原样投递，没有被加上模拟前缀
        assertThat(messages).noneMatch(m -> String.valueOf(m.get("content")).contains("模拟人工客服"));
        verifyNoInteractions(adapter);
        sessions.closeSession(s.getId());
    }

    @Test
    void concurrentClaimsGiveTheSessionToExactlyOneOperator() throws Exception {
        var s = sessions.createSession("u_claim", "web");
        support.transfer(s.getId(), "u_claim");

        int seats = 6;
        var pool = Executors.newFixedThreadPool(seats);
        var start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger(), lost = new AtomicInteger();
        var futures = IntStream.range(0, seats).mapToObj(i -> pool.submit(() -> {
            start.await();
            try { console.claim(s.getId(), "seat_" + i, "坐席" + i); won.incrementAndGet(); }
            catch (RuntimeException expected) { lost.incrementAndGet(); }
            return null;
        })).toList();
        start.countDown();
        for (var f : futures) f.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(won.get()).isEqualTo(1);
        assertThat(lost.get()).isEqualTo(seats - 1);
        sessions.closeSession(s.getId());
    }

    @Test
    void unclaimedSessionRejectsRepliesAndClosingReturnsControlToTheBot() {
        var s = sessions.createSession("u_close", "web");
        support.transfer(s.getId(), "u_close");

        assertThatThrownBy(() -> console.reply(s.getId(), "seat_x", "还没认领就回复"))
                .isInstanceOf(IllegalStateException.class);

        console.claim(s.getId(), "seat_x", "坐席X");
        assertThatThrownBy(() -> console.reply(s.getId(), "seat_y", "抢别人的会话"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> console.reply(s.getId(), "seat_x", "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(support.assigned(s.getId())).isTrue();
        console.close(s.getId(), "seat_x", "已解决");
        // 结束接管后会话交还智能客服，否则会永远停在人工模式
        assertThat(support.assigned(s.getId())).isFalse();
        assertThat(console.customerView(s.getId(), "u_close")).containsEntry("assigned", false);
        sessions.closeSession(s.getId());
    }
}
