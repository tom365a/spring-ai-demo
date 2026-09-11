package com.demo.cs.application.resources;
import com.demo.cs.agent.runtime.ToolBindingFactory;
import com.demo.cs.agent.model.AgentModels.ToolCallRecord;
import com.demo.cs.domain.CsToolInvocation;
import com.demo.cs.infrastructure.persistence.CsToolInvocationRepository;
import com.demo.cs.infrastructure.resources.transport.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Component
public class ToolExecutionGateway {
 private final ResourceService resources;private final HttpToolTransport http;private final McpToolTransport mcp;private final ToolBindingFactory builtins;private final ObjectMapper json;private final CsToolInvocationRepository audit;
 private static final ThreadLocal<Boolean> MANAGED=new ThreadLocal<>();
 public static boolean managedExecution(){return Boolean.TRUE.equals(MANAGED.get());}
 public ToolExecutionGateway(ResourceService resources,HttpToolTransport http,McpToolTransport mcp,ToolBindingFactory builtins,ObjectMapper json,CsToolInvocationRepository audit){this.resources=resources;this.http=http;this.mcp=mcp;this.builtins=builtins;this.json=json;this.audit=audit;}
 public record Result(String content,ToolCallRecord trace) {}
 public boolean confirmationRequired(ResourceSnapshot t,boolean configured){return t.config().path("sideEffect").asText().equals("WRITE")||t.config().path("requireConfirm").asBoolean()||configured;}
 public Result execute(String sessionId,ResourceSnapshot tool,ResourceSnapshot service,JsonNode arguments,Duration budget,boolean writeAllowed,boolean confirmed){
 resources.available(tool,false);if(service!=null)resources.available(service,false);
 boolean write=tool.config().path("sideEffect").asText().equals("WRITE");
 if(write&&!writeAllowed)throw new SecurityException("写工具未授权");
 if(confirmationRequired(tool,false)&&!confirmed)throw new SecurityException("工具执行尚未获确认");
 JsonSchemaGuard.arguments(tool.config().path("inputSchema"),arguments);
 long begin=System.nanoTime();String source=tool.config().path("source").asText();String state="SUCCEEDED",content;
 Duration timeout=Duration.ofMillis(Math.min(budget.toMillis(),tool.config().path("timeoutSeconds").asLong(30)*1000));
 if(timeout.isZero()||timeout.isNegative())throw new IllegalStateException("任务预算已耗尽");
 FutureTask<String> task=new FutureTask<>(()->{
 MANAGED.set(true);try{return switch(source){
 case "BUILTIN" -> {var callbacks=builtins.resolve(List.of(tool.config().path("builtinCode").asText(tool.code())));if(callbacks.length!=1)throw new IllegalStateException("内置工具绑定失败");
 // 内置工具在这条虚拟线程上执行，调用方线程的会话上下文传不过来；需要 sessionId 的工具（转人工）靠这里拿。
 com.demo.cs.infrastructure.tools.ToolSessionContext.set(sessionId);
 try{yield callbacks[0].call(arguments.toString());}finally{com.demo.cs.infrastructure.tools.ToolSessionContext.clear();}}
 case "HTTP" -> http.execute(tool.config(),arguments,resources.credential(tool),timeout).toString();
 case "MCP" -> {if(service==null)throw new IllegalStateException("MCP服务快照缺失");yield mcp.call(service.config(),tool.config().path("nativeName").asText(),arguments,resources.credential(service),timeout).toString();}
 default -> throw new IllegalStateException("工具来源不可执行");
 };}finally{MANAGED.remove();}});
 Thread.startVirtualThread(task);
 try{content=task.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
 catch(TimeoutException e){task.cancel(true);state=write?"UNKNOWN":"TIMEOUT";content=write?"写请求结果不确定，禁止自动重试":"工具执行超时";}
 catch(InterruptedException e){task.cancel(true);Thread.currentThread().interrupt();state=write?"UNKNOWN":"CANCELLED";content="工具执行等待已取消";}
 catch(ExecutionException e){
   if(e.getCause() instanceof TransportException transport){boolean unsent=Set.of("TARGET_DENIED","MAPPING_ERROR","CREDENTIAL_MISSING","INVALID_HEADER","INVALID_AUTH").contains(transport.code());state=write&&!unsent?"UNKNOWN":transport.code();content=transport.getMessage()+(state.equals("UNKNOWN")?"；写请求结果不确定，禁止自动重试":"");}
   else {state=write?"UNKNOWN":"FAILED";content=write?"写请求结果不确定，请核验外部系统后再操作":"工具执行失败，请检查资源配置或连接状态";}
 }
 if(state.equals("SUCCEEDED")&&content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768){state="RESULT_TOO_LARGE";content="工具结果超过32KiB，已停止后续调用";}
 if(state.equals("SUCCEEDED")&&source.equals("BUILTIN"))try{JsonNode result=json.readTree(content);
 // 工具返回的是 JSON 字符串，框架会再包一层引号；不解开就永远拿到 TextNode，业务拒绝判定会整段失效。
 if(result.isTextual())result=json.readTree(result.asText());
 if(result.has("ok")&&!result.path("ok").asBoolean()||result.has("success")&&!result.path("success").asBoolean()){state="BUSINESS_REJECTED";content=result.path("message").asText("内置工具拒绝业务操作，请核对业务条件");}}catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}
 long latency=(System.nanoTime()-begin)/1_000_000;
 ToolCallRecord record=new ToolCallRecord(tool.code(),redact(arguments),"["+state+"] "+(state.equals("SUCCEEDED")?"工具调用完成":content),source,latency,state.equals("SUCCEEDED"),tool.id(),tool.version(),state,tool.config().path("nativeName").asText(null));
 CsToolInvocation invocation=new CsToolInvocation();invocation.setSessionId(sessionId);invocation.setToolName(tool.code());invocation.setSource(source);invocation.setLatencyMs(latency);
 invocation.setSuccess(state.equals("SUCCEEDED"));
 try{invocation.setArgsJson(json.writeValueAsString(record.arguments()));invocation.setResultJson(json.writeValueAsString(record));audit.save(invocation);}catch(Exception e){throw new IllegalStateException("工具结果审计未完成；不得自动重试");}
 if(!state.equals("SUCCEEDED"))throw new GatewayFailure(content,record);
 return new Result(content,record);
 }
 public static Map<String,Object> redact(JsonNode arguments){Map<String,Object> out=new LinkedHashMap<>();arguments.fieldNames().forEachRemaining(k->out.put(k,"[已脱敏]"));return out;}
 public static class GatewayFailure extends IllegalStateException {public final ToolCallRecord trace;public GatewayFailure(String message,ToolCallRecord trace){super(message);this.trace=trace;}}
}
