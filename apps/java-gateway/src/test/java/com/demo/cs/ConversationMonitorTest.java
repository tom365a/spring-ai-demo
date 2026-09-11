package com.demo.cs;
import com.demo.cs.application.session.*;
import com.demo.cs.config.AppProperties;
import com.demo.cs.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@DataJpaTest(showSql=false,properties="spring.jpa.hibernate.ddl-auto=create-drop")
@Import({SessionService.class,ConversationMonitor.class,ConversationMonitorTest.Beans.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ConversationMonitorTest {
 @TestConfiguration static class Beans {
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
  @Bean AppProperties props(){return new AppProperties(null,null,null,10,.55,null,null,null,null);}
 }
 @Autowired SessionService sessions; @Autowired ConversationMonitor monitor; @Autowired CsSessionRepository repo; @Autowired ConversationEventRepository events;
 @Test void idleTimeoutDoesNotResolveOrResurrectSession(){
  var s=sessions.createSession("idle-test","web");s.setLastInputAt(Instant.now().minusSeconds(301));repo.save(s);
  sessions.expireIdleSessions();var closed=sessions.getSession(s.getId());assertThat(closed.getStatus()).isEqualTo("closed");assertThat(closed.getResolutionStatus()).isEqualTo("PENDING");
  assertThat(sessions.activity(s.getId(),"idle-test").get("status")).isEqualTo("closed");sessions.clearConfirm(s.getId());sessions.setConfirmState(s.getId(),Map.of("id","pending"));
  sessions.appendMessage(s.getId(),"assistant","迟到的答复","worker",null,null,List.of());assertThat(sessions.getSession(s.getId()).getStatus()).isEqualTo("closed");
  assertThatThrownBy(()->sessions.appendMessage(s.getId(),"user","再次提问",null,null,null,List.of())).isInstanceOf(IllegalStateException.class);
  assertThat(events.findBySessionIdOrderByIdAsc(s.getId()).stream().filter(e->e.type.equals("SESSION_CLOSED")).count()).isEqualTo(1);
 }
 @Test void assistantAndAssessmentNeverExtendInputDeadlineButActivityDoes(){
  var s=sessions.createSession("activity-test","web");Instant before=Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS);s.setLastInputAt(before);repo.save(s);
  sessions.appendMessage(s.getId(),"assistant","答复","worker",null,null,List.of());monitor.assess(s.getId(),"RESOLVED","人工已确认");assertThat(sessions.getSession(s.getId()).getLastInputAt()).isEqualTo(before);
  assertThatThrownBy(()->sessions.activity(s.getId(),"another-user")).isInstanceOf(SecurityException.class);
  sessions.activity(s.getId(),"activity-test");assertThat(sessions.getSession(s.getId()).getLastInputAt()).isAfter(before);
 }
 @Test void nextUserMessageResetsAssessmentAndRetainsAuditAndTurns(){
  var s=sessions.createSession("multi-test","trial");sessions.appendMessage(s.getId(),"user","问题1",null,null,null,List.of());monitor.event(s.getId(),"t1","TURN_STARTED","supervisor",Map.of());monitor.event(s.getId(),"t1","AGENT_TRANSFER","supervisor",Map.of("targetAgent","worker"));
  sessions.appendMessage(s.getId(),"assistant","答复1","worker",null,null,List.of());monitor.assess(s.getId(),"RESOLVED","客户确认");sessions.appendMessage(s.getId(),"user","还有问题",null,null,null,List.of());
  var detail=monitor.detail(s.getId());assertThat(sessions.getSession(s.getId()).getResolutionStatus()).isEqualTo("PENDING");assertThat((List<?>)detail.get("messages")).hasSize(3);assertThat(detail.get("traceCoverage")).isEqualTo("RECORDED_TURNS_ONLY");assertThat(events.findBySessionIdOrderByIdAsc(s.getId())).anyMatch(e->e.type.equals("ASSESSMENT")).anyMatch(e->e.type.equals("ASSESSMENT_RESET"));
  assertThat(monitor.list(s.getId(),"PENDING","trial",0,20).get("total")).isEqualTo(1);
 }
 @Test void legacyMessagesAreNotFabricatedIntoRoutingEvidence(){var s=sessions.createSession("legacy-test","web");sessions.appendMessage(s.getId(),"assistant","历史答复","worker",null,null,List.of());assertThat(monitor.detail(s.getId()).get("traceCoverage")).isEqualTo("LEGACY_MESSAGES_ONLY");assertThatThrownBy(()->monitor.assess(s.getId(),"DONE","bad")).isInstanceOf(IllegalArgumentException.class);}
}
