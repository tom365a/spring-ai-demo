package com.demo.cs;
import com.demo.cs.application.support.*;
import com.demo.cs.application.session.*;
import com.demo.cs.agent.runtime.*;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.api.dto.ApiDtos.ChatRequest;
import com.demo.cs.config.AppProperties;
import com.demo.cs.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","app.support.mode=SIMULATED"})
@Import({HumanSupportService.class,SessionService.class,ConversationMonitor.class,HumanSupportTest.Beans.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class HumanSupportTest {
 @TestConfiguration static class Beans {
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
  @Bean AppProperties props(){return new AppProperties(null,null,null,10,.55,null,null,null,null);}
  @Bean DefinitionRegistry agents(){return mock(DefinitionRegistry.class);}
  @Bean SupportImAdapter adapter(){return mock(SupportImAdapter.class);}
 }
 @Autowired HumanSupportService support;@Autowired SessionService sessions;@Autowired DefinitionRegistry agents;@Autowired SupportImAdapter adapter;@Autowired SupportAssignmentRepository assignments;@Autowired ConversationEventRepository events;
 @BeforeEach void setup(){reset(agents,adapter);for(String code:HumanSupportService.CODES)when(agents.get(code)).thenReturn(Optional.of(mock(PublishedAgent.class)));}
 @Test void assignmentBalancesIsIdempotentAndPreservesSessionHistory(){
  var one=sessions.createSession("support-user","web");var two=sessions.createSession("support-user","web");
  sessions.appendMessage(one.getId(),"user","此前问题",null,null,null,List.of());sessions.setConfirmState(one.getId(),Map.of("id","old-confirmation"));
  var first=support.transfer(one.getId(),"support-user");var second=support.transfer(two.getId(),"support-user");
  assertThat(first.get("agentCode")).isNotEqualTo(second.get("agentCode"));assertThat(support.transfer(one.getId(),"support-user")).isEqualTo(first);
  assertThat(sessions.allMessages(one.getId())).hasSize(2);assertThat(sessions.getSession(one.getId()).getConfirmationPayload()).isNull();
  assertThat(events.findBySessionIdOrderByIdAsc(one.getId()).stream().filter(e->e.type.equals("HUMAN_ASSIGNED")).count()).isEqualTo(1);
  sessions.closeSession(one.getId());sessions.closeSession(two.getId());
 }
 @Test void closedAndForeignSessionsCannotTransfer(){var s=sessions.createSession("owner","web");assertThatThrownBy(()->support.transfer(s.getId(),"other")).isInstanceOf(SecurityException.class);sessions.closeSession(s.getId());assertThatThrownBy(()->support.transfer(s.getId(),"owner")).isInstanceOf(IllegalStateException.class);assertThat(support.assigned(s.getId())).isFalse();}
 @Test void assignedAgentReceivesHistoryAndProviderFailureStaysHonest(){
  var s=sessions.createSession("support-history","web");sessions.appendMessage(s.getId(),"user","原问题",null,null,null,List.of());support.transfer(s.getId(),"support-history");
  when(adapter.reply(any(),anyList())).thenAnswer(inv->{List<?> history=inv.getArgument(1);assertThat(history.toString()).contains("原问题","继续咨询");return "请补充问题细节";});
  var request=new ChatRequest(s.getId(),"support-history","继续咨询",List.of(),null,null,null,null,null);
  var reply=support.chat(request);assertThat(reply.answer()).contains("模拟人工客服","请补充");assertThat(reply.agentName()).isIn(HumanSupportService.CODES);
  doThrow(new IllegalStateException("secret-provider-error")).when(adapter).reply(any(),anyList());var failure=support.chat(request);assertThat(failure.answer()).contains("暂时不可用").doesNotContain("secret-provider-error");assertThat(sessions.getSession(s.getId()).getResolutionStatus()).isEqualTo("PENDING");
  sessions.closeSession(s.getId());
 }
}
