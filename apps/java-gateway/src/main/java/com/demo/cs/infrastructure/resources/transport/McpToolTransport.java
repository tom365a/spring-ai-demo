package com.demo.cs.infrastructure.resources.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Standard Streamable HTTP and explicitly separated legacy project bridge. No stdio execution. */
@Component
public class McpToolTransport {
    private static final Set<String> VERSIONS=Set.of("2025-11-25","2025-06-18","2025-03-26");
    private final ObjectMapper mapper;
    public McpToolTransport(ObjectMapper mapper) { this.mapper=mapper; }

    public List<JsonNode> discover(JsonNode config,String credential,Duration timeout) {
        if (legacy(config)) {
            JsonNode root=legacyRequest(config,"GET","/tools",null,credential,timeout);
            JsonNode tools=root.isArray()?root:root.has("tools")?root.path("tools"):root.path("data");
            return toolList(tools,credential);
        }
        try (Session session=new Session(config,credential,timeout)) {
            List<JsonNode> tools=new ArrayList<>(); Set<String> cursors=new HashSet<>(), names=new HashSet<>(); String cursor=null;
            for (int page=0;page<100;page++) {
                ObjectNode params=mapper.createObjectNode(); if(cursor!=null)params.put("cursor",cursor);
                JsonNode result=session.rpc("tools/list",params);
                for (JsonNode tool:toolList(result.path("tools"),credential)) {
                    if (!names.add(tool.path("name").asText())) throw fail("目录包含重复工具名称");
                    tools.add(tool);
                }
                if (tools.size()>1000) throw fail("工具目录超过1000项限制");
                cursor=result.path("nextCursor").isTextual()?result.path("nextCursor").asText():null;
                if(cursor==null || cursor.isEmpty()) return List.copyOf(tools);
                if(!cursors.add(cursor))throw fail("工具目录分页游标重复");
            }
            throw fail("工具目录超过分页限制");
        }
    }

    public JsonNode call(JsonNode config,String toolName,JsonNode arguments,String credential,Duration timeout) {
        if (toolName==null || toolName.isBlank()) throw fail("工具名称不能为空");
        JsonNode result;
        if (legacy(config)) result=legacyRequest(config,"POST","/tools/call",mapper.createObjectNode()
                .put("name",toolName).set("arguments",arguments),credential,timeout);
        else try (Session session=new Session(config,credential,timeout)) {
            result=session.rpc("tools/call",mapper.createObjectNode().put("name",toolName).set("arguments",arguments));
        }
        if (result.path("isError").asBoolean(false) || (result.has("ok") && !result.path("ok").asBoolean())) {
            throw new TransportException("MCP_TOOL_ERROR", "MCP 工具返回执行失败；已发送的写操作结果需核实");
        }
        result=HttpToolTransport.redact(result,credential);
        if (result.toString().getBytes(StandardCharsets.UTF_8).length>32768) throw new TransportException("RESULT_TOO_LARGE","MCP 工具结果超过32 KiB限制");
        return result;
    }

    private boolean legacy(JsonNode config) {
        String type=config.path("transport").asText("STREAMABLE_HTTP");
        if (!type.equals("STREAMABLE_HTTP") && !type.equals("LEGACY_BRIDGE")) throw fail("不支持的 MCP 传输类型");
        return type.equals("LEGACY_BRIDGE");
    }
    private List<JsonNode> toolList(JsonNode node,String secret) {
        if(!node.isArray())throw fail("工具目录响应缺少 tools 数组");
        List<JsonNode> result=new ArrayList<>(); Set<String> names=new HashSet<>();
        for(JsonNode tool:node) {
            String name=tool.path("name").asText();
            if(name.isBlank() || name.length()>256 || !names.add(name))throw fail("工具名称无效或重复");
            ObjectNode copy=mapper.createObjectNode().put("name",name).put("description",tool.path("description").asText(""));
            JsonNode schema=tool.path("inputSchema");
            if(schema.isMissingNode()) schema=mapper.createObjectNode().put("type","object").set("properties",mapper.createObjectNode());
            if(!schema.isObject())throw fail("工具输入 Schema 无效");
            copy.set("inputSchema",schema.deepCopy());
            copy.set("annotations",tool.path("annotations").isObject()?tool.path("annotations").deepCopy():mapper.createObjectNode());
            result.add(HttpToolTransport.redact(copy,secret));
        }
        return result;
    }
    private JsonNode legacyRequest(JsonNode config,String method,String suffix,JsonNode body,String secret,Duration timeout) {
        String url=config.path("url").asText().replaceAll("/+$","")+suffix;
        Map<String,String> headers=GuardedHttp.authHeaders(config,secret);headers.put("Accept","application/json");
        var response=GuardedHttp.request(config,method,url,headers,body==null?null:body.toString().getBytes(StandardCharsets.UTF_8),timeout,1_048_576);
        return parse(response.body());
    }
    private JsonNode parse(byte[] body) {
        try { JsonNode result=mapper.readTree(body);if(result==null)throw fail("响应为空");return result; }
        catch(IOException e){throw fail("响应不是有效 JSON");}
    }
    private static TransportException fail(String text) { return new TransportException("MCP_PROTOCOL_ERROR","MCP "+text); }

    private final class Session implements AutoCloseable {
        private final JsonNode config; private final String secret;
        private final long deadline; private String sessionId="",version="";
        private int sequence;
        Session(JsonNode config,String secret,Duration timeout) {
            this.config=config;this.secret=secret;this.deadline=System.nanoTime()+timeout.toNanos();
            ObjectNode params=mapper.createObjectNode().put("protocolVersion","2025-11-25");
            params.set("capabilities",mapper.createObjectNode());
            params.set("clientInfo",mapper.createObjectNode().put("name","spring-agent-resource-client").put("version","1.0"));
            try {
                JsonNode initialized=rpc("initialize",params);
                version=initialized.path("protocolVersion").asText();
                if(!VERSIONS.contains(version))throw fail("服务返回不支持的协议版本");
                if(!initialized.path("capabilities").has("tools"))throw fail("服务未声明 tools 能力");
                sendNotification(mapper.createObjectNode().put("jsonrpc","2.0").put("method","notifications/initialized"));
            } catch(RuntimeException error) {close();throw error;}
        }
        Duration remaining() {
            long ns=deadline-System.nanoTime();
            if(ns<=0)throw new TransportException("TIMEOUT","MCP 调用总预算已耗尽");
            return Duration.ofNanos(ns);
        }
        Map<String,String> headers() {
            Map<String,String> headers=GuardedHttp.authHeaders(config,secret);
            headers.put("Accept","application/json, text/event-stream");
            if(!sessionId.isEmpty())headers.put("Mcp-Session-Id",sessionId);
            if(!version.isEmpty())headers.put("MCP-Protocol-Version",version);
            return headers;
        }
        JsonNode rpc(String method,JsonNode params) {
            String id="resource-"+(++sequence);
            ObjectNode body=mapper.createObjectNode().put("jsonrpc","2.0").put("id",id).put("method",method);body.set("params",params);
            return GuardedHttp.exchange(config,"POST",config.path("url").asText(),headers(),body.toString().getBytes(StandardCharsets.UTF_8),remaining(),(status,responseHeaders,input)-> {
                if(method.equals("initialize")) {
                    String received=responseHeaders.getOrDefault("mcp-session-id","");
                    if(received.length()>1024 || received.chars().anyMatch(c->c<33 || c>126))throw fail("会话标识无效");
                    sessionId=received;
                }
                String type=responseHeaders.getOrDefault("content-type","").toLowerCase(Locale.ROOT);
                JsonNode response;
                if(type.startsWith("text/event-stream")) response=readSse(input,id);
                else if(type.startsWith("application/json")) response=parse(GuardedHttp.readBounded(input,1_048_576));
                else throw fail("响应 Content-Type 不支持");
                if(!response.path("jsonrpc").asText().equals("2.0") || !response.path("id").isTextual() || !response.path("id").asText().equals(id))throw fail("响应 ID 或协议版本不匹配");
                if(response.has("error"))throw new TransportException("MCP_REMOTE_ERROR","MCP 服务返回协议错误，错误码 "+response.path("error").path("code").asInt());
                if(!response.has("result"))throw fail("响应缺少 result");
                return response.path("result");
            });
        }
        void sendNotification(JsonNode body) {
            var response=GuardedHttp.request(config,"POST",config.path("url").asText(),headers(),body.toString().getBytes(StandardCharsets.UTF_8),remaining(),4096);
            if(response.status()!=202)throw fail("通知未被202接受");
        }
        JsonNode readSse(InputStream input,String id)throws IOException {
            ByteArrayOutputStream line=new ByteArrayOutputStream(); StringBuilder data=new StringBuilder(); int total=0;
            while(true) {
                int b=input.read();
                if(b>=0 && ++total>1_048_576)throw new TransportException("RESPONSE_TOO_LARGE","MCP 流超过大小限制");
                if(b>=0 && b!='\n') {line.write(b);continue;}
                String value=line.toString(StandardCharsets.UTF_8);line.reset();if(value.endsWith("\r"))value=value.substring(0,value.length()-1);
                if(value.isEmpty() || b<0) {
                    if(!data.isEmpty()) {
                        JsonNode event=parse(data.toString().getBytes(StandardCharsets.UTF_8));data.setLength(0);
                        if(event.has("method") && event.has("id")) {
                            ObjectNode reply=mapper.createObjectNode().put("jsonrpc","2.0");reply.set("id",event.get("id"));
                            if(event.path("method").asText().equals("ping"))reply.set("result",mapper.createObjectNode());
                            else reply.set("error",mapper.createObjectNode().put("code",-32601).put("message","Client capability not supported"));
                            sendNotification(reply);
                        } else if(event.has("id")) {
                            if(event.path("id").asText().equals(id))return event;
                            throw fail("流响应 ID 不匹配");
                        }
                    }
                    if(b<0)throw fail("流在最终响应前断开，本次调用不自动重试");
                } else if(value.startsWith("data:")) {
                    String piece=value.substring(5);if(piece.startsWith(" "))piece=piece.substring(1);
                    if(!piece.isEmpty()){if(!data.isEmpty())data.append('\n');data.append(piece);}
                }
            }
        }
        public void close() {
            if(sessionId.isEmpty() || System.nanoTime()>=deadline)return;
            try {GuardedHttp.request(config,"DELETE",config.path("url").asText(),headers(),null,
                    Duration.ofNanos(Math.min(1_000_000_000L,deadline-System.nanoTime())),1024);}
            catch(RuntimeException ignored) { /* Session cleanup cannot replace the actual call result. */ }
        }
    }
}
