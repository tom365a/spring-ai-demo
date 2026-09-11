package com.demo.cs;
import com.demo.cs.application.resources.*;
import com.demo.cs.application.agentconfig.*;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.agent.model.AgentModels.*;
import com.demo.cs.api.dto.AdminDtos.*;
import com.demo.cs.api.dto.ApiDtos.ChatRequest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:resource-runtime;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
"app.agent-config.seed-on-startup=false","app.upload-dir=./target/resource-self-test","app.knowledge-sample-dir=./target/no-samples",
"OPENAI_API_KEY=synthetic-self-test-only","app.vector.backend=lexical","spring.ai.model.embedding=none"})
@ActiveProfiles("local")
class ResourceRuntimeSelfTest {
 @Autowired ResourceService resources;@Autowired ManagedAgentRuntime runtime;@Autowired AgentDefinitionService definitions;@Autowired AgentPublishService publisher;@Autowired ObjectMapper json;
 @Autowired com.demo.cs.application.session.SessionService sessions;
 @Autowired com.demo.cs.application.attachment.AttachmentService attachments;
 @Autowired com.demo.cs.infrastructure.persistence.ConversationEventRepository monitorEvents;
 @MockitoBean ResourceModelFactory factory;
 HttpServer server;AtomicInteger writes;AtomicInteger rejects;ChatModel model;
 @BeforeEach void start()throws Exception {writes=new AtomicInteger();rejects=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/action",ex->{writes.incrementAndGet();byte[] b="{\"ok\":true}".getBytes();ex.getRequestBody().readAllBytes();ex.getResponseHeaders().add("Content-Type","application/json");ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);ex.close();});server.createContext("/reject",ex->{rejects.incrementAndGet();byte[] b="{\"ok\":true,\"message\":\"已按您的选择拒绝该单据\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);ex.getRequestBody().readAllBytes();ex.getResponseHeaders().add("Content-Type","application/json");ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);ex.close();});
 server.start();model=mock(ChatModel.class);when(factory.create(any(),anyString(),any())).thenReturn(model);}
 @AfterEach void stop(){server.stop(0);}
 String code(){return "r_"+UUID.randomUUID().toString().replace("-","").substring(0,12);}
 String tool(String risk){
 ObjectNode c=json.createObjectNode().put("source","HTTP").put("sideEffect",risk).put("requireConfirm",risk.equals("WRITE")).put("method","POST").put("url","http://127.0.0.1:"+server.getAddress().getPort()+"/action");
 c.putObject("target").put("scheme","http").put("host","127.0.0.1").put("port",server.getAddress().getPort()).put("allowPrivate",true);
 c.putObject("auth").put("type","NONE");c.putArray("mappings");c.putObject("inputSchema").put("type","object").putObject("properties");
 ObjectNode b=json.createObjectNode().put("kind","TOOL").put("code",code()).put("name","自测工具");b.set("config",c);var saved=resources.save(null,b,true);String id=saved.get("id").toString();
 resources.lifecycle(id,"publish",json.createObjectNode().put("impactToken",resources.impact(id,"publish").get("token").toString()));return saved.get("code").toString();
 }
 /** 带「二选一」元数据的写工具：确认执行自己，取消改为执行 rejectCode。 */
 String choiceTool(String path,String rejectCode){
 ObjectNode c=json.createObjectNode().put("source","HTTP").put("sideEffect","WRITE").put("requireConfirm",true).put("method","POST").put("url","http://127.0.0.1:"+server.getAddress().getPort()+path);
 c.putObject("target").put("scheme","http").put("host","127.0.0.1").put("port",server.getAddress().getPort()).put("allowPrivate",true);
 c.putObject("auth").put("type","NONE");c.putArray("mappings");c.putObject("inputSchema").put("type","object").putObject("properties");
 if(rejectCode!=null)c.putObject("choice").put("prompt","请确认这笔待处理的单据").put("confirmLabel","同意").put("cancelLabel","拒绝").put("rejectTool",rejectCode);
 ObjectNode b=json.createObjectNode().put("kind","TOOL").put("code",code()).put("name","二选一工具");b.set("config",c);var saved=resources.save(null,b,true);String id=saved.get("id").toString();
 resources.lifecycle(id,"publish",json.createObjectNode().put("impactToken",resources.impact(id,"publish").get("token").toString()));return saved.get("code").toString();
 }

 String agent(List<String> tools,int rounds)throws Exception {
 String code=code();ObjectNode n=json.createObjectNode().put("code",code).put("name","执行自测").put("type","WORKER");
 n.putObject("prompts").put("systemPrompt","测试").put("userPromptTemplate","{{text}}");n.set("tools",json.valueToTree(tools));
 n.putObject("policies").put("allowWriteTools",true).put("maxToolRounds",rounds);
 AgentDefinition d=json.treeToValue(n,AgentDefinition.class);definitions.create(new CreateAgentRequest(code,d.name(),d.type(),"",d),"selftest");publisher.enable(code,"selftest");return code;
 }
 ChatResponse batch(String tool,int count){List<AssistantMessage.ToolCall> calls=new ArrayList<>();for(int i=0;i<count;i++)calls.add(new AssistantMessage.ToolCall("call_"+i,"function",tool,"{}"));return new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),calls))));}
 ChatResponse answer(){return new ChatResponse(List.of(new Generation(new AssistantMessage("执行完成"))));}
 @Test void actualWriteWaitsForOpaqueConfirmationAndReplayNeverExecutesTwice()throws Exception {
 String tool=tool("WRITE"),agent=agent(List.of(tool),3);when(model.call(any(Prompt.class))).thenReturn(batch(tool,1),answer());
 var out=runtime.trial(agent,"操作",false,false,true);assertThat(out.confirmRequired()).isTrue();assertThat(writes.get()).isZero();String id=out.confirmationPayload().get("id").toString();
 var done=runtime.confirm(id,out.sessionId(),true);assertThat(done.confirmRequired()).isFalse();assertThat(writes.get()).isEqualTo(1);
 assertThatThrownBy(()->runtime.confirm(id,out.sessionId(),true)).isInstanceOf(IllegalStateException.class);assertThat(writes.get()).isEqualTo(1);
 assertThat(done.toolCalls()).anyMatch(t->"SUCCEEDED".equals(t.state())&&t.resourceVersion()!=null);
 assertThat(monitorEvents.findBySessionIdOrderByIdAsc(out.sessionId()).stream().filter(e->e.type.equals("TOOL_RESULT")&&e.dataJson.contains("SUCCEEDED")).count()).isEqualTo(1);
 }
 @Test void twoWayChoiceRendersBothButtonsAndCancelRunsTheRejectTool()throws Exception {
 String reject=choiceTool("/reject",null);
 String agree=choiceTool("/action",reject);
 String agent=agent(List.of(agree,reject),3);
 when(model.call(any(Prompt.class))).thenReturn(batch(agree,1),answer());

 var out=runtime.trial(agent,"处理这笔单据",false,false,true);
 assertThat(out.confirmRequired()).isTrue();
 // 卡片上两个按钮各代表一个业务动作，文案由工具配置给出，不再是「确认执行/取消操作」。
 assertThat(out.confirmationPayload()).containsEntry("confirmLabel","同意").containsEntry("cancelLabel","拒绝");
 assertThat(String.valueOf(out.confirmationPayload().get("summary"))).isEqualTo("请确认这笔待处理的单据");
 assertThat(writes.get()).isZero();assertThat(rejects.get()).isZero();

 var done=runtime.confirm(out.confirmationPayload().get("id").toString(),out.sessionId(),false);
 // 点「拒绝」= 真正执行拒绝动作，而不是静默放弃；同意那一侧一次也没被执行。
 assertThat(rejects.get()).isEqualTo(1);
 assertThat(writes.get()).isZero();
 assertThat(done.confirmRequired()).isFalse();
 assertThat(done.answer()).isEqualTo("已按您的选择拒绝该单据");
 }

 @Test void plainWriteCancelStillDoesNothing()throws Exception {
 String tool=tool("WRITE"),agent=agent(List.of(tool),3);when(model.call(any(Prompt.class))).thenReturn(batch(tool,1));
 var out=runtime.trial(agent,"操作",false,false,true);
 assertThat(out.confirmationPayload()).doesNotContainKeys("confirmLabel","cancelLabel");
 var done=runtime.confirm(out.confirmationPayload().get("id").toString(),out.sessionId(),false);
 assertThat(done.answer()).isEqualTo("已取消该操作。");assertThat(writes.get()).isZero();
 }

 @Test void expiredSessionCannotExecutePendingWrite()throws Exception {
 String tool=tool("WRITE"),agent=agent(List.of(tool),3);when(model.call(any(Prompt.class))).thenReturn(batch(tool,1));var out=runtime.trial(agent,"操作",false,false,true);
 sessions.closeSession(out.sessionId());assertThatThrownBy(()->runtime.confirm(out.confirmationPayload().get("id").toString(),out.sessionId(),true)).isInstanceOf(IllegalStateException.class);assertThat(writes.get()).isZero();
 }
 @Test void zeroRoundsAndOversizedBatchRejectBeforeAnyTransport()throws Exception {
 String tool=tool("READ"),zero=agent(List.of(tool),0);when(model.call(any(Prompt.class))).thenReturn(batch(tool,1));
 assertThat(runtime.trial(zero,"操作",false,false,true).answer()).contains("轮数");assertThat(writes.get()).isZero();
 String normal=agent(List.of(tool),3);when(model.call(any(Prompt.class))).thenReturn(batch(tool,11));
 assertThat(runtime.trial(normal,"操作",false,false,true).answer()).contains("10项");assertThat(writes.get()).isZero();
 }
 @Test void twoToolsInOneBatchConsumeOneRoundAndResumeDoesNotRepeatRead()throws Exception {
 String read=tool("READ"),write=tool("WRITE"),agent=agent(List.of(read,write),1);
 var response=new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("r","function",read,"{}"),new AssistantMessage.ToolCall("w","function",write,"{}"))))));
 when(model.call(any(Prompt.class))).thenReturn(response,answer());var out=runtime.trial(agent,"操作",false,false,true);
 assertThat(writes.get()).isEqualTo(1);assertThat(out.confirmRequired()).isTrue();
 var done=runtime.confirm(out.confirmationPayload().get("id").toString(),out.sessionId(),true);assertThat(writes.get()).isEqualTo(2);
 assertThat(done.answer()).isEqualTo("执行完成");assertThat(done.diagnostics().toString()).contains("toolRounds=1");
 }
 @Test void revokedToolAndConcurrentConfirmCannotWrite()throws Exception {
 String tool=tool("WRITE"),agent=agent(List.of(tool),3);when(model.call(any(Prompt.class))).thenReturn(batch(tool,1));var out=runtime.trial(agent,"操作",false,false,true);
 resources.lifecycle(tool,"disable",json.createObjectNode().put("impactToken",resources.impact(tool,"disable").get("token").toString()));
 var result=runtime.confirm(out.confirmationPayload().get("id").toString(),out.sessionId(),true);assertThat(result.answer()).contains("失效");assertThat(writes.get()).isZero();
 }
 @Test void draftToolTestUsesUnifiedWriteConfirmation() {
 String tool=tool("WRITE");var out=runtime.test(tool,json.createObjectNode(),true);assertThat(out.get("confirmRequired")).isEqualTo(true);assertThat(writes.get()).isZero();
 @SuppressWarnings("unchecked")Map<String,Object> payload=(Map<String,Object>)out.get("confirmationPayload");
 runtime.confirm(payload.get("id").toString(),payload.get("sessionId").toString(),true);assertThat(writes.get()).isEqualTo(1);
 }
 @Test void builtinBusinessRejectionIsReportedAsRejectedNotSucceeded() {
 // 内置工具返回 {"ok":false,...} 时框架会把整串再包一层引号；不解开就会把业务拒绝报成成功。
 ObjectNode args=json.createObjectNode().put("orderId","ORD20260730001").put("userId","u_001").put("reason","一次");
 var first=runtime.test("cancel_order",args,true);
 @SuppressWarnings("unchecked")Map<String,Object> p1=(Map<String,Object>)first.get("confirmationPayload");
 var done=runtime.confirm(p1.get("id").toString(),p1.get("sessionId").toString(),true);
 assertThat(done.toolCalls()).anyMatch(t->t.name().equals("cancel_order")&&t.success());

 var second=runtime.test("cancel_order",args,true);
 @SuppressWarnings("unchecked")Map<String,Object> p2=(Map<String,Object>)second.get("confirmationPayload");
 var rejected=runtime.confirm(p2.get("id").toString(),p2.get("sessionId").toString(),true);
 assertThat(rejected.toolCalls()).anyMatch(t->t.name().equals("cancel_order")&&!t.success()&&t.state().equals("BUSINESS_REJECTED"));
 assertThat(rejected.answer()).contains("不可取消");
 }
 @Test void builtinTestAcceptsSchemaMetadataWithoutResolvingIt() {
 ObjectNode args=json.createObjectNode().put("userId","u_001").put("category","其他").put("description","自测工单").put("priority","P3");
 var out=runtime.test("create_ticket",args,true);assertThat(out.get("confirmRequired")).isEqualTo(true);
 @SuppressWarnings("unchecked")Map<String,Object> payload=(Map<String,Object>)out.get("confirmationPayload");
 var done=runtime.confirm(payload.get("id").toString(),payload.get("sessionId").toString(),true);
 assertThat(done.toolCalls()).anyMatch(t->t.name().equals("create_ticket")&&t.success());
 assertThatThrownBy(()->JsonSchemaGuard.definition(json.createObjectNode().put("type","object").put("$ref","https://example.invalid/schema"))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void olderExplicitSkillPinCanConfirmWhenNewerVersionAlreadyExists()throws Exception {
 String tool=tool("WRITE"),skillCode=code();ObjectNode c=json.createObjectNode().put("instructions","skill-v1-private-instruction");c.putArray("applicableTypes").add("WORKER");c.putArray("tools").add(tool);
 ObjectNode b=json.createObjectNode().put("kind","SKILL").put("code",skillCode).put("name","技能版本自测");b.set("config",c);String id=resources.save(null,b,true).get("id").toString();
 resources.lifecycle(id,"publish",json.createObjectNode().put("impactToken",resources.impact(id,"publish").get("token").toString()));
 b.put("draftRevision",resources.require(id).draftRevision);c.put("instructions","skill-v2-do-not-inject");resources.save(id,b,true);resources.lifecycle(id,"publish",json.createObjectNode().put("impactToken",resources.impact(id,"publish").get("token").toString()));
 String agent=agent(List.of(),3);var detail=definitions.get(agent);ObjectNode definition=(ObjectNode)json.valueToTree(detail.draft());definition.putArray("skills").add(skillCode);definition.putObject("skillVersions").put(skillCode,1);
 definitions.update(agent,new UpdateAgentRequest(null,null,null,null,detail.draftRevision(),json.treeToValue(definition,AgentDefinition.class),null,null),"selftest");publisher.publish(agent,new PublishRequest("pin v1"),"selftest");
 when(model.call(any(Prompt.class))).thenAnswer(invocation->{Prompt p=invocation.getArgument(0);assertThat(p.getSystemMessage().getText()).contains("skill-v1-private-instruction").doesNotContain("skill-v2-do-not-inject");return p.getInstructions().stream().anyMatch(m->m instanceof ToolResponseMessage)?answer():batch(tool,1);});
 var out=runtime.trial(agent,"操作",false,false,true);assertThat(out.confirmRequired()).isTrue();var done=runtime.confirm(out.confirmationPayload().get("id").toString(),out.sessionId(),true);assertThat(done.answer()).isEqualTo("执行完成");assertThat(writes.get()).isEqualTo(1);
 }
 @Test void migratedDefaultVisionCapabilityPreservesImageThroughMainAndWorker()throws Exception {
 String worker=agent(List.of(),3),main=code();ObjectNode d=json.createObjectNode().put("code",main).put("name","图片路由自测").put("type","SUPERVISOR");d.putObject("prompts").put("systemPrompt","image-main").put("userPromptTemplate","{{text}}");d.putArray("children").addObject().put("agentCode",worker);
 AgentDefinition def=json.treeToValue(d,AgentDefinition.class);definitions.create(new CreateAgentRequest(main,def.name(),def.type(),"",def),"selftest");publisher.enable(main,"selftest");
 var session=sessions.createSession("u_001","test");byte[] image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j/ssAAAAASUVORK5CYII=");
 var attachment=attachments.upload(new org.springframework.mock.web.MockMultipartFile("file","sample.png","image/png",image),session.getId());
 AtomicInteger seen=new AtomicInteger();when(model.call(any(Prompt.class))).thenAnswer(invocation->{Prompt p=invocation.getArgument(0);assertThat(p.getInstructions().stream().filter(m->m instanceof UserMessage).map(m->(UserMessage)m).anyMatch(m->!m.getMedia().isEmpty())).isTrue();seen.incrementAndGet();return new ChatResponse(List.of(new Generation(new AssistantMessage(p.getSystemMessage().getText().contains("image-main")?"{\"targetAgent\":\""+worker+"\",\"confidence\":1,\"intent\":\"image\"}":"图片已处理"))));});
 var out=runtime.chat(new ChatRequest(session.getId(),"u_001","看这张图片",List.of(attachment.getId()),null,null,"zh-CN",null,main),null);assertThat(out.answer()).isEqualTo("图片已处理");assertThat(seen.get()).isEqualTo(2);
 var chain=monitorEvents.findBySessionIdOrderByIdAsc(out.sessionId());assertThat(chain).anyMatch(e->e.type.equals("INTENT_RECOGNIZED")).anyMatch(e->e.type.equals("AGENT_TRANSFER")&&e.dataJson.contains(worker)).anyMatch(e->e.type.equals("TURN_COMPLETED"));
 }
}
