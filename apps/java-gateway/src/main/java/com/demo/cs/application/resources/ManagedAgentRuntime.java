package com.demo.cs.application.resources;

import com.demo.cs.agent.model.AgentModels.*;
import com.demo.cs.agent.runtime.*;
import com.demo.cs.api.dto.ApiDtos.ChatRequest;
import com.demo.cs.application.agentconfig.AgentDefinitionService;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.application.knowledge.KnowledgeService;
import com.demo.cs.application.session.SessionService;
import com.demo.cs.domain.*;
import com.demo.cs.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Shared task engine for production chat, single-agent trials and administrator tool tests. */
@Service @Order(5)
public class ManagedAgentRuntime implements ApplicationRunner {
 private final ResourceService resources;private final AgentResourceResolver resolver;private final ResourceModelFactory models;
 private final ToolExecutionGateway gateway;private final DefinitionRegistry registry;private final AgentDefinitionService definitions;
 private final SessionService sessions;private final PromptTemplateRenderer renderer;private final KnowledgeService knowledge;
 private final PendingResourceActionRepository pendingRepo;private final ObjectMapper json;private final TransactionTemplate tx;
 private final CsAttachmentRepository attachments;
 private final com.demo.cs.application.session.ConversationMonitor monitor;
 private final Map<String,Task> pending=new ConcurrentHashMap<>();
 private final Map<String,Object> sessionLocks=new ConcurrentHashMap<>();
 private final Map<String,Task> invalidatedBudgets=new ConcurrentHashMap<>();
 public ManagedAgentRuntime(ResourceService resources,AgentResourceResolver resolver,ResourceModelFactory models,ToolExecutionGateway gateway,
 DefinitionRegistry registry,AgentDefinitionService definitions,SessionService sessions,PromptTemplateRenderer renderer,KnowledgeService knowledge,
 PendingResourceActionRepository pendingRepo,ObjectMapper json,PlatformTransactionManager manager,CsAttachmentRepository attachments,com.demo.cs.application.session.ConversationMonitor monitor){
 this.resources=resources;this.resolver=resolver;this.models=models;this.gateway=gateway;this.registry=registry;this.definitions=definitions;
 this.sessions=sessions;this.renderer=renderer;this.knowledge=knowledge;this.pendingRepo=pendingRepo;this.json=json;this.tx=new TransactionTemplate(manager);
 this.attachments=attachments;this.monitor=monitor;
 }
 public void run(ApplicationArguments ignored) {tx.executeWithoutResult(s->{for(String state:List.of("PENDING","EXECUTING"))for(var p:pendingRepo.findByState(state)){p.state=state.equals("EXECUTING")?"UNKNOWN":"INVALIDATED";pendingRepo.save(p);}});}
 private static final class AgentState {
 final AgentDefinition def;final Integer version;final int revision;final AgentResourceResolver.Resolved resources;
 final List<Message> messages=new ArrayList<>();int rounds;long remaining;List<AssistantMessage.ToolCall> calls=List.of();int next;
 final List<ToolResponseMessage.ToolResponse> batchResults=new ArrayList<>();
 AgentState(AgentDefinition def,Integer version,int revision,AgentResourceResolver.Resolved resources){this.def=def;this.version=version;this.revision=revision;this.resources=resources;this.remaining=def.policies().taskTimeoutSeconds()*1000L;}
 }
 private static final class Task {
 String id=UUID.randomUUID().toString(),sessionId,userId,text,summary,rootCode,currentCode,actionId;boolean route=true,persist=true,writeTestAllowed;int transfers;int loggedTraces;boolean handoffAvailable;String handoffReason;
 String intent="managed_route",reason="资源快照执行";double confidence=1;
 long rootRemaining;Map<String,AgentState> agents=new LinkedHashMap<>();List<Map<String,String>> history=List.of();
 List<ToolCallRecord> traces=new ArrayList<>();List<Citation> citations=new ArrayList<>();List<Map<String,Object>> modelTrace=new ArrayList<>();
 List<org.springframework.ai.content.Media> media=new ArrayList<>();
 boolean toolTest; ResourceSnapshot testTool,testService;JsonNode testArguments;
 }
 public TurnOutcome chat(ChatRequest request,Consumer<Map<String,Object>> events) {
 String user=Objects.requireNonNullElse(request.userId(),"u_001");CsSession s=request.sessionId()==null?sessions.createSession(user,"web"):sessions.getSession(request.sessionId());
 if(s==null)throw new IllegalArgumentException("会话不存在");if(!s.getUserId().equals(user))throw new SecurityException("userId mismatch");
 synchronized(sessionLocks.computeIfAbsent(s.getId(),k->new Object())) {
 if(s.getStatus().equals("closed"))throw new IllegalStateException("会话已关闭");
 Map<String,Object> old=sessions.getConfirmationPayload(s);
 if(Boolean.TRUE.equals(request.confirm())&&old!=null&&old.get("id")!=null)return confirm(old.get("id").toString(),s.getId(),true);
 if(old!=null&&old.get("id")!=null){String oldId=old.get("id").toString();pending.remove(oldId);mark(oldId,"INVALIDATED");sessions.clearConfirm(s.getId());}
 String code=request.supervisorCode()==null||request.supervisorCode().isBlank()?"supervisor":request.supervisorCode();
 PublishedAgent root=registry.get(code).orElseThrow(()->new IllegalArgumentException("主Agent不存在或未启用"));
 if(!root.definition().type().equals("SUPERVISOR"))throw new IllegalArgumentException("请选择主Agent");
 Task task=start(s,request.text(),root.definition(),root.version(),true,true);
 if(request.attachmentIds()!=null)for(String attachmentId:request.attachmentIds()){
 var attachment=attachments.findById(attachmentId).orElseThrow(()->new IllegalArgumentException("附件不存在"));
 if(attachment.getSessionId()!=null&&!attachment.getSessionId().equals(s.getId()))throw new SecurityException("附件不属于当前会话");
 task.media.add(new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.parseMimeType(attachment.getContentType()),new org.springframework.core.io.FileSystemResource(attachment.getFilePath())));
 }
 sessions.appendMessage(s.getId(),"user",task.text,null,null,null,request.attachmentIds());
 TurnOutcome out=drive(task,false);persist(task,out);return out;
 }
 }
 private Task start(CsSession session,String text,AgentDefinition def,Integer version,boolean route,boolean persist){
 if(text==null||text.isBlank())throw new IllegalArgumentException("text 必填");
 Task t=new Task();t.sessionId=session.getId();t.userId=session.getUserId();t.text=text;t.summary=session.getSummary();t.rootCode=def.code();t.currentCode=def.code();t.route=route;t.persist=persist;
 t.rootRemaining=def.policies().taskTimeoutSeconds()*1000L;t.history=publicHistory(session.getId());
 capture(t,def,version);
 if(route)for(var child:def.children())if(child!=null&&child.childEnabled())registry.get(child.agentCode()).filter(p->p.definition().type().equals("WORKER")).ifPresent(p->capture(t,p.definition(),p.version()));
 Task previous=invalidatedBudgets.remove(t.sessionId);if(previous!=null){t.rootRemaining=Math.min(t.rootRemaining,previous.rootRemaining);t.agents.forEach((code,a)->{AgentState old=previous.agents.get(code);if(old!=null){a.rounds=old.rounds;a.remaining=Math.min(a.remaining,old.remaining);}});}
 monitor.event(t.sessionId,t.id,"TURN_STARTED",t.rootCode,Map.of("mode",route?"SUPERVISOR":"SINGLE_AGENT"));
 return t;
 }
 private void capture(Task task,AgentDefinition def,Integer version){int revision=definitions.require(def.code()).getDraftRevision();task.agents.put(def.code(),new AgentState(def,version,revision,resolver.resolve(def)));}
 private List<Map<String,String>> publicHistory(String id) {
 List<Map<String,String>> out=new ArrayList<>();String user=null;
 for(var m:sessions.allMessages(id)) {if(m.getRole().equals("user"))user=m.getContent();else if(m.getRole().equals("assistant")&&user!=null){
 boolean pendingMessage=false;try{JsonNode tools=m.getToolCallsJson()==null?null:json.readTree(m.getToolCallsJson());if(tools!=null)for(JsonNode tool:tools)if(tool.path("state").asText().equals("PENDING"))pendingMessage=true;}catch(Exception ignored){}
 if(!pendingMessage){out.add(Map.of("user",Objects.requireNonNullElse(user,""),"assistant",Objects.requireNonNullElse(m.getContent(),"")));user=null;}}}
 return List.copyOf(out);
 }
 private void available(Task t,boolean strict) {
 var session=sessions.getSession(t.sessionId);if(session==null||"closed".equals(session.getStatus()))throw new IllegalStateException("会话已结束，请新建会话");
 for(AgentState a:t.agents.values()){
 var entity=definitions.require(a.def.code());if(a.version!=null){var maybe=registry.get(a.def.code());if(maybe.isEmpty()&&!strict&&!a.def.code().equals(t.rootCode)&&!a.def.code().equals(t.currentCode))continue;var active=maybe.orElseThrow(()->new IllegalStateException("Agent已停用: "+a.def.code()));if(strict&&active.version()!=a.version)throw new IllegalStateException("Agent版本已变化");}
 else if(entity.getDraftRevision()!=a.revision)throw new IllegalStateException("试运行草稿已变化");
 resolver.available(a.resources,strict);
 }
 if(t.rootRemaining<=0)throw new IllegalStateException("主任务时间预算已耗尽");
 }
 private void initialize(Task t,AgentState a) {
 if(!a.messages.isEmpty())return;
 List<Map<String,String>> history=t.history.subList(Math.max(0,t.history.size()-a.def.memory().windowSize()),t.history.size());
 String children=t.agents.values().stream().filter(v->!v.def.code().equals(t.rootCode)).map(v->v.def.code()+": "+v.def.name()+" — "+Objects.requireNonNullElse(v.def.description(),"")).reduce("",(x,y)->x+y+"\n");
 String retrieved="";
 if(resolver.rag(a.def,a.resources)){var docs=knowledge.retrieveDocuments(t.text,5);t.citations.addAll(knowledge.toCitations(docs));retrieved=docs.stream().map(org.springframework.ai.document.Document::getText).reduce("",(x,y)->x+y+"\n");}
 Map<String,String> vars=new HashMap<>();for(String key:PromptTemplateRenderer.WHITELIST)vars.put(key,"");
 vars.put("userId",t.userId);vars.put("summary",a.def.memory().injectSummary()?Objects.requireNonNullElse(t.summary,""):"");
 vars.put("childrenCatalog",children);vars.put("agentDescription",Objects.requireNonNullElse(a.def.description(),""));vars.put("text",t.text);vars.put("retrievedBlocks",retrieved);vars.put("locale","zh-CN");vars.put("slots","{}");vars.put("slotsJson","{}");vars.put("hasAttachments",String.valueOf(!t.media.isEmpty()));vars.put("attachmentCount",String.valueOf(t.media.size()));
 // Historical public turns are real chat messages, never serialized twice through prompt placeholders.
 String system=renderer.render(a.def.prompts().systemPrompt(),vars)+resolver.instructions(a.resources);
 if(a.def.memory().injectSummary()&&t.summary!=null&&!t.summary.isBlank())system+="\n公开会话摘要："+t.summary;
 if(!retrieved.isBlank())system+="\n知识检索结果：\n"+retrieved;
 if(t.route&&a.def.code().equals(t.rootCode))system+="\n你是主Agent。只能从以下可用子Agent中选择一次转交：\n"+children+"\n最终仅输出JSON {\"targetAgent\":\"code或none\",\"intent\":\"简短意图\",\"confidence\":1,\"reason\":\"原因\",\"needClarify\":false,\"clarifyQuestion\":\"问题\"}。禁止转交列表外Agent。用户业务已明确但没有匹配能力时，返回targetAgent=none且needClarify=false；只有信息不足时才needClarify=true。";
 a.messages.add(new SystemMessage(system));
 for(var h:history){a.messages.add(new UserMessage(h.get("user")));a.messages.add(new AssistantMessage(h.get("assistant")));}
 String user=renderer.render(a.def.prompts().userPromptTemplate(),vars);if(user.isBlank())user=t.text;
 if(!t.media.isEmpty()&&a.resources.model().config().path("capabilities").path("vision").asBoolean(false))a.messages.add(UserMessage.builder().text(user).media(t.media).build());
 else {if(!t.media.isEmpty()&&!a.def.code().equals(t.rootCode))throw new IllegalStateException("当前子Agent模型未声明图片能力");a.messages.add(new UserMessage(user));}
 }
 private TurnOutcome drive(Task t,boolean confirmed) {
 if(t.toolTest)return testDrive(t,confirmed);
 if(t.route&&t.agents.size()==1)return outcome(t,"当前主Agent没有可用子Agent，请启用关联子Agent。",false,null);
 try {
 while(true) {
 AgentState a=t.agents.get(t.currentCode);long setupStart=System.nanoTime();try{available(t,false);if(a.remaining<=0)throw new IllegalStateException("Agent时间预算已耗尽");initialize(t,a);}finally{consume(t,a,setupStart);}
 if(!a.calls.isEmpty()) {
 while(a.next<a.calls.size()) {
 available(t,false);var call=a.calls.get(a.next);ResourceSnapshot tool=a.resources.tools().get(call.name());
 if(tool==null)throw new SecurityException("模型请求未授权工具");
 JsonNode args=arguments(call.arguments());JsonSchemaGuard.arguments(tool.config().path("inputSchema"),args);
 boolean needs=gateway.confirmationRequired(tool,a.def.policies().requireConfirmFor().contains(tool.code()));
 if(needs&&!confirmed)return pause(t,a,tool,args);
 ResourceSnapshot service=tool.config().path("source").asText().equals("MCP")?a.resources.services().get(tool.config().path("serviceId").asText()):null;
 long begin=System.nanoTime();
 try {
 var result=gateway.execute(t.sessionId,tool,service,args,Duration.ofMillis(Math.min(Math.min(t.rootRemaining,a.remaining),a.def.policies().toolTimeoutSeconds()*1000L)),a.def.type().equals("WORKER")&&a.def.policies().writeToolsAllowed(),confirmed);
 t.traces.add(result.trace());logTool(t,result.trace());a.batchResults.add(new ToolResponseMessage.ToolResponse(call.id(),call.name(),result.content()));a.next++;
 }catch(ToolExecutionGateway.GatewayFailure e){t.traces.add(e.trace);logTool(t,e.trace);throw e;}finally{consume(t,a,begin);}
 confirmed=false;
 }
 a.messages.add(new ToolResponseMessage(List.copyOf(a.batchResults)));a.calls=List.of();a.batchResults.clear();a.next=0;
 }
 available(t,false);long begin=System.nanoTime();ChatResponse response;
 try {
 var options=ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).toolCallbacks(a.resources.tools().values().stream().map(this::definitionCallback).toList()).build();
 response=models.create(a.resources.model(),resources.credential(a.resources.model()),Duration.ofMillis(Math.min(t.rootRemaining,a.remaining))).call(new Prompt(a.messages,options));
 }catch(RuntimeException failure){monitor.event(t.sessionId,t.id,"MODEL_FAILED",a.def.code(),Map.of("resourceId",a.resources.model().id(),"latencyMs",(System.nanoTime()-begin)/1_000_000,"message","模型调用失败"));throw failure;}finally{consume(t,a,begin);}
 monitor.event(t.sessionId,t.id,"MODEL_COMPLETED",a.def.code(),Map.of("resourceId",a.resources.model().id(),"model",Objects.requireNonNullElse(response.getMetadata().getModel(),""),"latencyMs",(System.nanoTime()-begin)/1_000_000));
 Map<String,Object> md=new LinkedHashMap<>();var model=a.resources.model();md.put("agentCode",a.def.code());md.put("agentVersion",a.version);md.put("resourceId",model.id());md.put("resourceVersion",model.version());md.put("provider",model.config().path("provider").asText());md.put("model",response.getMetadata().getModel());md.put("usage",response.getMetadata().getUsage());t.modelTrace.add(md);
 AssistantMessage assistant=response.getResult().getOutput();a.messages.add(assistant);
 if(assistant.hasToolCalls()) {
 if(assistant.getToolCalls().size()>10)throw new IllegalStateException("工具批次超过10项，整批未执行");
 if(a.rounds>=a.def.policies().maxToolRounds())throw new IllegalStateException("工具调用轮数已耗尽");
 Set<String> ids=new HashSet<>();for(var call:assistant.getToolCalls()){if(call.id()==null||call.id().isBlank()||!ids.add(call.id())||!a.resources.tools().containsKey(call.name()))throw new IllegalStateException("工具批次包含非法身份，整批未执行");}
 a.rounds++;a.calls=List.copyOf(assistant.getToolCalls());continue;
 }
 String answer=Objects.requireNonNullElse(assistant.getText(),"");
 if(t.route&&a.def.code().equals(t.rootCode)&&t.transfers==0) {
 JsonNode route=route(answer);String target=route.path("targetAgent").asText("none");
 t.intent=route.path("intent").asText("managed_route");t.reason=route.path("reason").asText("主Agent路由");t.confidence=route.path("confidence").asDouble(0);
 monitor.event(t.sessionId,t.id,"INTENT_RECOGNIZED",a.def.code(),Map.of("intent",t.intent,"confidence",t.confidence,"targetAgent",target,"needClarify",route.path("needClarify").asBoolean()));
 double threshold=a.def.routing()!=null&&a.def.routing().confidenceThreshold()!=null?a.def.routing().confidenceThreshold():0.55;
 if(target.equals("none")&&!route.path("needClarify").asBoolean()){t.handoffAvailable=true;t.handoffReason="NO_MATCHING_AGENT";return outcome(t,"当前智能体暂时无法处理该问题，可以转人工客服。",false,null);}
 if(target.equals("none")||route.path("needClarify").asBoolean()||t.confidence<threshold)return outcome(t,route.path("clarifyQuestion").asText("请补充问题信息后继续。"),false,null);
 AgentState child=t.agents.get(target);if(child==null||target.equals(t.rootCode)||!child.def.type().equals("WORKER"))throw new IllegalStateException("主Agent返回未授权子Agent");
 // A child disabled after snapshot is filtered at transfer; no alternate stale dispatch.
 registry.get(target).orElseThrow(()->new IllegalStateException("目标子Agent已停用"));t.transfers++;monitor.event(t.sessionId,t.id,"AGENT_TRANSFER",a.def.code(),Map.of("targetAgent",target,"intent",t.intent));t.currentCode=target;continue;
 }
 return outcome(t,answer,false,null);
 }
 }catch(RuntimeException e){return outcome(t,"执行已停止："+safeMessage(e),false,null);}
 }
 private JsonNode route(String text){try{String s=text.trim();if(s.startsWith("```"))s=s.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");return json.readTree(s);}catch(Exception e){throw new IllegalStateException("主Agent返回的路由格式不正确");}}
 private String safeMessage(RuntimeException e){return e instanceof IllegalArgumentException||e instanceof IllegalStateException||e instanceof SecurityException?Objects.requireNonNullElse(e.getMessage(),"配置或连接错误"):"模型或工具服务暂时不可用";}
 private JsonNode arguments(String raw){try{return json.readTree(raw);}catch(Exception e){throw new IllegalArgumentException("工具参数不是有效JSON");}}
 private void consume(Task t,AgentState a,long start){long ms=Math.max(1,(System.nanoTime()-start)/1_000_000);t.rootRemaining-=ms;a.remaining-=ms;}
 private ToolCallback definitionCallback(ResourceSnapshot tool){return new ToolCallback(){
 public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(tool.code()).description(tool.name()).inputSchema(tool.config().path("inputSchema").toString()).build();}
 public String call(String arguments){throw new IllegalStateException("禁止模型直接执行工具，必须经统一执行网关");}
 };}
 private TurnOutcome pause(Task t,AgentState a,ResourceSnapshot tool,JsonNode args) {
 String id=UUID.randomUUID().toString();PendingResourceAction p=new PendingResourceAction();p.id=id;p.sessionId=t.sessionId;p.state="PENDING";p.expiresAt=Instant.now().plus(Duration.ofMinutes(30));
 Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("taskId",t.id);snapshot.put("tool",tool);snapshot.put("arguments",args);snapshot.put("remainingMs",t.rootRemaining);snapshot.put("rounds",a==null?1:a.rounds);snapshot.put("next",a==null?0:a.next);
 try{p.snapshotJson=json.writeValueAsString(snapshot);}catch(Exception e){throw new IllegalStateException("无法保存确认快照");}
 tx.executeWithoutResult(s->pendingRepo.save(p));t.actionId=id;pending.put(id,t);
 Map<String,Object> payload=new LinkedHashMap<>();payload.put("id",id);payload.put("sessionId",t.sessionId);payload.put("expiresAt",p.expiresAt);payload.put("summary","执行工具："+tool.name());payload.put("tool",Map.of("code",tool.code(),"version",tool.version(),"source",tool.config().path("source").asText(),"arguments",ToolExecutionGateway.redact(args)));
 t.traces.add(new ToolCallRecord(tool.code(),ToolExecutionGateway.redact(args),"等待用户确认",tool.config().path("source").asText(),0,false,tool.id(),tool.version(),"PENDING",tool.config().path("nativeName").asText(null)));
 return outcome(t,"请确认是否执行："+tool.name(),true,payload);
 }
 public TurnOutcome confirm(String id,String sessionId,boolean decision) {
 synchronized(sessionLocks.computeIfAbsent(sessionId,k->new Object())){var session=sessions.getSession(sessionId);if(session==null||session.getStatus().equals("closed"))throw new IllegalStateException("确认会话不可用");var payload=sessions.getConfirmationPayload(session);if(payload==null||!id.equals(payload.get("id")))throw new IllegalStateException("确认已失效，请重新发起");return confirmLocked(id,sessionId,decision);}
 }
 private TurnOutcome confirmLocked(String id,String sessionId,boolean decision) {
 Task task=tx.execute(s->{var p=pendingRepo.locked(id).orElseThrow(()->new IllegalArgumentException("确认不存在"));
 if(!p.sessionId.equals(sessionId))throw new SecurityException("确认不属于该会话");
 if(!p.state.equals("PENDING"))throw new IllegalStateException("确认已处理、失效或不可重试");
 if(p.expiresAt.isBefore(Instant.now())){p.state="EXPIRED";pendingRepo.save(p);return null;}
 Task t=pending.get(id);if(t==null){p.state="INVALIDATED";pendingRepo.save(p);return null;}
 p.state=decision?"EXECUTING":"REJECTED";pendingRepo.save(p);return t;});
 if(task==null)throw new IllegalStateException("确认已过期或服务重启后失效，请重新发起");
 pending.remove(id);
 task.traces.removeIf(trace->"PENDING".equals(trace.state()));task.loggedTraces=Math.min(task.loggedTraces,task.traces.size());
 monitor.event(task.sessionId,task.id,decision?"CONFIRMATION_ACCEPTED":"CONFIRMATION_CANCELLED",task.currentCode,Map.of("confirmationId",id));
 if(!decision){TurnOutcome out=outcome(task,"已取消该操作。",false,null);persist(task,out);return out;}
 try{if(task.toolTest){resources.available(task.testTool,true);if(task.testService!=null)resources.available(task.testService,true);}else available(task,true);}
 catch(RuntimeException e){mark(id,"INVALIDATED");invalidatedBudgets.put(task.sessionId,task);TurnOutcome out=outcome(task,"相关版本或启用状态已变化，原确认失效；请在此会话重新发起。已耗预算不会退还。",false,null);persist(task,out);return out;}
 TurnOutcome out=drive(task,true);mark(id,out.answer().startsWith("执行已停止")?"FAILED":"SUCCEEDED");persist(task,out);return out;
 }
 private void mark(String id,String state){tx.executeWithoutResult(s->{var p=pendingRepo.findById(id).orElseThrow();p.state=state;pendingRepo.save(p);});}
 private TurnOutcome outcome(Task t,String answer,boolean confirm,Map<String,Object> payload) {
 Map<String,Object> diagnostics=new LinkedHashMap<>();diagnostics.put("taskId",t.id);if(t.handoffAvailable||answer.startsWith("执行已停止")){diagnostics.put("handoffAvailable",true);diagnostics.put("handoffReason",t.handoffReason==null?"EXECUTION_FAILED":t.handoffReason);}diagnostics.put("models",List.copyOf(t.modelTrace));diagnostics.put("remainingMs",Math.max(0,t.rootRemaining));diagnostics.put("transfers",t.transfers);Map<String,Object> budgets=new LinkedHashMap<>();t.agents.forEach((code,a)->budgets.put(code,Map.of("toolRounds",a.rounds,"maxToolRounds",a.def.policies().maxToolRounds(),"remainingMs",Math.max(0,a.remaining))));diagnostics.put("agents",budgets);
 return new TurnOutcome(t.sessionId,answer,t.toolTest?"tool_test":t.route?t.intent:"trial",t.currentCode,t.confidence,t.reason,t.citations,List.copyOf(t.traces),confirm,payload,diagnostics);
 }
 private void logTool(Task t,ToolCallRecord trace){monitor.event(t.sessionId,t.id,"TOOL_RESULT",t.currentCode,Map.of("tool",trace.name(),"state",Objects.requireNonNullElse(trace.state(),"UNKNOWN"),"success",trace.success(),"latencyMs",trace.latencyMs(),"source",Objects.requireNonNullElse(trace.source(),"")));}
 private void persist(Task t,TurnOutcome out) {
 if(!t.persist)return;
 monitor.event(t.sessionId,t.id,out.confirmRequired()?"CONFIRMATION_PENDING":out.answer().startsWith("执行已停止")?"TURN_FAILED":"TURN_COMPLETED",t.currentCode,Map.of("intent",t.intent,"message",out.confirmRequired()?"等待用户确认":out.answer().startsWith("执行已停止")?"执行失败，详见公开对话":"已返回答复，是否解决需评价"));
 if(out.confirmRequired())sessions.setConfirmState(t.sessionId,out.confirmationPayload());else sessions.clearConfirm(t.sessionId);
 sessions.appendMessage(t.sessionId,"assistant",out.answer(),out.agentName(),out.citations(),out.toolCalls(),List.of());
 if(!out.confirmRequired()){sessions.updateRouting(t.sessionId,out.intent(),out.agentName());String summary=t.text+"\n"+out.answer();sessions.updateSummary(t.sessionId,summary.substring(0,Math.min(2000,summary.length())));}
 }
 public TurnOutcome trial(String code,String text,boolean draft,boolean fullRoute,boolean publisher) {
 var entity=definitions.require(code);var def=draft?definitions.readDefinition(entity.getDraftJson()):registry.get(code).orElseThrow(()->new IllegalStateException("Agent未启用")).definition();
 if(fullRoute&&draft)throw new IllegalArgumentException("全链路只支持已发布Agent");
 if(!publisher&&resolver.resolve(def).tools().values().stream().anyMatch(t->t.config().path("sideEffect").asText().equals("WRITE")))throw new SecurityException("写工具试运行需要publisher角色");
 var session=sessions.createSession("trial_"+UUID.randomUUID(),"trial");Task task=start(session,text,def,draft?null:entity.getPublishedVersion(),fullRoute,true);
 sessions.appendMessage(session.getId(),"user",text,null,null,null,List.of());TurnOutcome out=drive(task,false);persist(task,out);return out;
 }
 public Map<String,Object> test(String id,JsonNode arguments,boolean publisher) {
 var entity=resources.require(id);JsonNode d=resources.parse(entity.draftJson);resources.validateConfig(entity.kind,d.path("config"),true);var snapshot=new ResourceSnapshot(entity.id,entity.code,entity.kind,entity.name,-entity.draftRevision,d.path("config"),entity.credentialRef);
 if(entity.kind.equals("MODEL")) {try{var response=models.create(snapshot,resources.credential(snapshot),Duration.ofSeconds(snapshot.config().path("timeoutSeconds").asInt(30))).call(new Prompt("Reply OK only."));resources.testResult(entity.id,entity.draftRevision,"SUCCESS");return Map.of("success",true,"message","模型连接成功","diagnostics",Map.of("provider",snapshot.config().path("provider").asText(),"model",response.getMetadata().getModel(),"resourceId",entity.id,"draftRevision",entity.draftRevision),"result","OK");}catch(RuntimeException e){resources.testResult(entity.id,entity.draftRevision,"FAILED");throw e;}}
 if(entity.kind.equals("MCP"))return resources.sync(id);
 if(!entity.kind.equals("TOOL"))throw new IllegalArgumentException("技能通过Agent试运行验证");
 if(snapshot.config().path("sideEffect").asText().equals("WRITE")&&!publisher)throw new SecurityException("写工具测试需要publisher角色");
 // Tests use the exact selected published identity; publish draft first to avoid alternate executable authority.
 var session=sessions.createSession("tool_test_"+UUID.randomUUID(),"tool_test");Task t=new Task();t.toolTest=true;t.sessionId=session.getId();t.userId=session.getUserId();t.currentCode="tool_test";t.rootCode="tool_test";t.rootRemaining=120000;t.testTool=snapshot;t.testArguments=arguments;t.writeTestAllowed=publisher;
 if(snapshot.config().path("source").asText().equals("MCP"))t.testService=resources.published(snapshot.config().path("serviceId").asText(),"MCP",null);
 TurnOutcome out=testDrive(t,false);persist(t,out);Map<String,Object> result=new LinkedHashMap<>(out.toChatResponseMap());result.put("success",!out.answer().startsWith("执行已停止"));result.put("message",out.answer());result.put("result",out.answer());return result;
 }
 private TurnOutcome testDrive(Task t,boolean confirmed) {
 try {JsonSchemaGuard.arguments(t.testTool.config().path("inputSchema"),t.testArguments);if(gateway.confirmationRequired(t.testTool,false)&&!confirmed)return pause(t,null,t.testTool,t.testArguments);
 long begin=System.nanoTime();var result=gateway.execute(t.sessionId,t.testTool,t.testService,t.testArguments,Duration.ofMillis(t.rootRemaining),t.writeTestAllowed,confirmed);t.rootRemaining-=(System.nanoTime()-begin)/1_000_000;t.traces.add(result.trace());logTool(t,result.trace());resources.testResult(t.testTool.id(),-t.testTool.version(),"SUCCESS");return outcome(t,result.content(),false,null);
 }catch(ToolExecutionGateway.GatewayFailure e){t.traces.add(e.trace);logTool(t,e.trace);resources.testResult(t.testTool.id(),-t.testTool.version(),"FAILED");return outcome(t,"执行已停止："+e.getMessage(),false,null);}
 }
}
