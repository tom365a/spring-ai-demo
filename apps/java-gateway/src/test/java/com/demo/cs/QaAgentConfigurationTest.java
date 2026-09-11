package com.demo.cs;

import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.api.dto.AdminDtos.*;
import com.demo.cs.application.agentconfig.*;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.infrastructure.persistence.AgtAgentVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Independent QA: real MVC, persistence, lifecycle and transaction failure injection. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:qa;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
 "app.agent-config.seed-on-startup=false","app.upload-dir=./target/qa-uploads","app.knowledge-sample-dir=./target/no-samples"})
@ActiveProfiles("local") @AutoConfigureMockMvc
class QaAgentConfigurationTest {
 @Autowired AgentDefinitionService definitions;
 @Autowired AgentPublishService publisher;
 @Autowired DefinitionRegistry registry;
 @Autowired AgtAgentVersionRepository versions;
 @Autowired ObjectMapper mapper;
 @Autowired MockMvc mvc;
 @MockitoSpyBean PlatformTransactionManager manager;
 @BeforeEach void resetSpies(){ reset(manager); }
 String code(){return "qa_"+UUID.randomUUID().toString().replace("-","").substring(0,12);}
 ObjectNode body(String code,String type){
  var n=mapper.createObjectNode();n.put("code",code);n.put("name","测试名称");n.put("type",type);
  n.putObject("definition").put("code",code).put("name","测试名称").put("type",type)
    .putObject("prompts").put("systemPrompt","QA_V1");return n;
 }
 AgentDetailResponse create(ObjectNode n)throws Exception{return definitions.create(mapper.treeToValue(n,CreateAgentRequest.class),"qa");}
 AgentDetailResponse worker()throws Exception{return create(body(code(),"WORKER"));}
 int postStatus(String path,String role,ObjectNode body)throws Exception{
  var request=post(path).header("X-Admin-Role",role);
  if(body!=null)request.contentType("application/json").content(mapper.writeValueAsBytes(body));
  return mvc.perform(request).andReturn().getResponse().getStatus();
 }
 @Test void commitFailureRollsBackDatabaseAndNeverLeaksIntoRegistry()throws Exception{
  var w=worker();String code=w.draft().code();publisher.enable(code,"qa");
  var changed=mapper.valueToTree(w.draft());((ObjectNode)changed.path("prompts")).put("systemPrompt","QA_UNCOMMITTED");
  definitions.update(code,new UpdateAgentRequest(null,null,null,null,w.draftRevision(),mapper.treeToValue(changed,AgentDefinition.class),null,null),"qa");
  doAnswer(call->{
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
    @Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("QA injected commit failure");}
   });return call.callRealMethod();
  }).when(manager).commit(any(TransactionStatus.class));
  assertThatThrownBy(()->publisher.publish(code,new PublishRequest("commit fail"),"qa")).hasMessageContaining("QA injected commit failure");
  reset(manager);
  assertThat(definitions.get(code).publishedVersion()).isEqualTo(1);
  assertThat(versions.findByAgentCodeOrderByVersionDesc(code)).hasSize(1);
  assertThat(registry.get(code).orElseThrow().definition().prompts().systemPrompt()).isEqualTo("QA_V1");
 }
 @Test void databaseConstraintFailureLeavesNoPublishedOrLoadedVersion()throws Exception{
  var w=worker();String code=w.draft().code();
  assertThatThrownBy(()->publisher.publish(code,new PublishRequest("x".repeat(501)),"qa")).isInstanceOf(RuntimeException.class);
  assertThat(definitions.get(code).enabled()).isFalse();assertThat(definitions.get(code).publishedVersion()).isNull();
  assertThat(versions.findByAgentCodeOrderByVersionDesc(code)).isEmpty();assertThat(registry.get(code)).isEmpty();
 }
 @Test void concurrentPublishAndDisableAlwaysAgreeWithDatabase()throws Exception{
  for(int round=0;round<8;round++){
   String code=worker().draft().code();publisher.enable(code,"qa");
   var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
   try{
    var p=pool.submit(()->{start.await();return publisher.publish(code,new PublishRequest("race"),"qa");});
    var d=pool.submit(()->{start.await();return publisher.disable(code,"qa");});start.countDown();p.get(10,TimeUnit.SECONDS);d.get(10,TimeUnit.SECONDS);
    var state=definitions.get(code);assertThat(registry.get(code).isPresent()).isEqualTo(state.enabled());
    registry.get(code).ifPresent(runtime->assertThat(runtime.version()).isEqualTo(state.publishedVersion()));
   }finally{pool.shutdownNow();}
  }
 }
 @Test void invalidInputsNeverCreateRowsAndUseClientErrors()throws Exception{
  List<ObjectNode> invalid=new ArrayList<>();
  var blank=body(code(),"WORKER");blank.put("name","  ");invalid.add(blank);
  var longName=body(code(),"WORKER");longName.put("name","n".repeat(129));invalid.add(longName);
  var badType=body(code(),"ALIEN");invalid.add(badType);
  var badCode=body("INVALID-CODE","WORKER");invalid.add(badCode);
  for(String field:List.of("tools","skills")){
   var unknown=body(code(),"WORKER");((ObjectNode)unknown.path("definition")).putArray(field).add("unknown");invalid.add(unknown);
   var nil=body(code(),"WORKER");((ObjectNode)nil.path("definition")).putArray(field).addNull();invalid.add(nil);
  }
  for(String prompt:List.of(" ","{{unknown}}","x".repeat(20001))){var n=body(code(),"WORKER");((ObjectNode)n.path("definition").path("prompts")).put("systemPrompt",prompt);invalid.add(n);}
  for(var n:invalid){int status=postStatus("/api/v1/admin/agents","editor",n);assertThat(status).as(n.toString().substring(0,Math.min(200,n.toString().length()))).isBetween(400,499);assertThatThrownBy(()->definitions.get(n.path("code").asText())).isInstanceOf(IllegalArgumentException.class);}
 }
 @Test void relationshipConstraintsAndReenableRevalidation()throws Exception{
  String worker=worker().draft().code();publisher.enable(worker,"qa");
  var main=body(code(),"SUPERVISOR");((ObjectNode)main.path("definition")).putArray("children").addObject().put("agentCode",worker);
  String mainCode=create(main).draft().code();publisher.enable(mainCode,"qa");publisher.disable(worker,"qa");publisher.disable(mainCode,"qa");
  assertThatThrownBy(()->publisher.enable(mainCode,"qa")).isInstanceOf(AgentValidationException.class);assertThat(registry.get(mainCode)).isEmpty();
  for(String kind:List.of("self","duplicate","main","missing","worker_parent","null")){
   var n=body(code(),kind.equals("worker_parent")?"WORKER":"SUPERVISOR");var children=((ObjectNode)n.path("definition")).putArray("children");
   switch(kind){case "self"->children.addObject().put("agentCode",n.path("code").asText());case "duplicate"->{children.addObject().put("agentCode",worker);children.addObject().put("agentCode",worker);}case "main"->children.addObject().put("agentCode",mainCode);case "null"->children.addNull();default->children.addObject().put("agentCode",kind.equals("missing")?"qa_nonexistent":worker);}
   assertThat(postStatus("/api/v1/admin/agents","editor",n)).as(kind).isEqualTo(422);
  }
 }
 @Test void allLifecycleActionsRequirePublisherAndCannotUseUpdateBypass()throws Exception{
  String code=worker().draft().code();
  for(String role:List.of("viewer","editor","invalid"))for(String action:List.of("enable","disable","publish","rollback")){
   var b=mapper.createObjectNode();if(action.equals("rollback"))b.put("version",1);assertThat(postStatus("/api/v1/admin/agents/"+code+"/"+action,role,b)).as(role+" "+action).isEqualTo(403);
  }
  int bypass=mvc.perform(put("/api/v1/admin/agents/"+code).header("X-Admin-Role","editor").contentType("application/json").content("{\"enabled\":true}")).andReturn().getResponse().getStatus();
  assertThat(bypass).isEqualTo(400);assertThat(registry.get(code)).isEmpty();
 }
 @Test void duplicateCodeAndStaleRevisionAreConflicts()throws Exception{
  var n=body(code(),"WORKER");var w=create(n);assertThat(postStatus("/api/v1/admin/agents","editor",n)).isEqualTo(409);
  var update=new UpdateAgentRequest("changed",null,null,null,0,null,null,null);definitions.update(w.draft().code(),update,"qa");
  var r=mvc.perform(put("/api/v1/admin/agents/"+w.draft().code()).header("X-Admin-Role","editor").contentType("application/json").content(mapper.writeValueAsBytes(update))).andReturn().getResponse();
  assertThat(r.getStatus()).isEqualTo(409);assertThat(definitions.get(w.draft().code()).draft().name()).isEqualTo("changed");
 }
}
