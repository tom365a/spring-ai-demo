package com.demo.cs.infrastructure.resources.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class HttpToolTransport {
    private final ObjectMapper mapper;
    public HttpToolTransport(ObjectMapper mapper) { this.mapper=mapper; }

    public JsonNode execute(JsonNode config, JsonNode arguments, String credential, Duration timeout) {
        String method=config.path("method").asText("GET").toUpperCase(Locale.ROOT);
        if (!Set.of("GET","POST","PUT","PATCH","DELETE").contains(method)) throw fail("不支持的请求方法");
        String url=config.path("url").asText();
        Map<String,String> headers=GuardedHttp.authHeaders(config,credential);
        headers.put("Accept","application/json");
        Set<String> reserved = new HashSet<>(Set.of("authorization","proxy-authorization","host","cookie","content-length","transfer-encoding","connection","content-type","accept","upgrade","te","trailer"));
        headers.keySet().forEach(n -> reserved.add(n.toLowerCase(Locale.ROOT)));
        String query=""; JsonNode body=null; Set<String> destinations=new HashSet<>(); boolean rootBody=false;
        for (JsonNode mapping:config.path("mappings")) {
            String kind=mapping.path("sourceKind").asText("INPUT");
            JsonNode value;
            if (kind.equals("INPUT")) value=JsonPaths.read(arguments,mapping.path("source").asText());
            else if (kind.equals("LITERAL")) value=mapping.path("literal");
            else throw fail("不支持的参数来源");
            if (value.isMissingNode()) {
                if (!mapping.path("required").asBoolean(true)) continue;
                throw fail("参数映射缺少必需的输入字段");
            }
            String target=mapping.path("target").asText();
            String targetKind=mapping.path("targetKind").asText();
            String identity=targetKind+":"+(targetKind.equals("HEADER") ? target.toLowerCase(Locale.ROOT) : target);
            if (!destinations.add(identity)) throw fail("参数映射目标重复");
            switch (targetKind) {
                case "PATH" -> {
                    int authorityEnd=url.indexOf('/',url.indexOf("://")+3);
                    int location=url.indexOf("{"+target+"}");
                    if (!target.matches("[A-Za-z_][A-Za-z0-9_]*") || authorityEnd<0 || location<authorityEnd || (url.indexOf('?')>=0 && location>url.indexOf('?'))) throw fail("路径映射只能替换固定 URL 路径中的占位符");
                    String text=scalar(value);
                    if (text.equals(".") || text.equals("..") || text.contains("/") || text.contains("\\")) throw fail("路径参数不能改变路由层级");
                    url=url.replace("{"+target+"}",encode(text));
                }
                case "QUERY" -> {
                    if (target.isBlank()) throw fail("查询参数名称不能为空");
                    query+=(query.isEmpty()?"":"&")+encode(target)+"="+encode(scalar(value));
                }
                case "HEADER" -> {
                    if (reserved.contains(target.toLowerCase(Locale.ROOT))) throw fail("业务参数不能覆盖认证或传输 Header");
                    GuardedHttp.validHeader(target,scalar(value)); headers.put(target,scalar(value));
                }
                case "BODY" -> {
                    if (method.equals("GET")) throw fail("GET 工具不能配置请求体");
                    if (rootBody || (target.equals("$") && body!=null)) throw fail("整体请求体不能与字段映射混用");
                    rootBody=target.equals("$"); body=JsonPaths.write(body,target,value);
                }
                default -> throw fail("不支持的映射目标位置");
            }
        }
        if (url.contains("{") || url.contains("}")) throw fail("URL 存在未绑定占位符");
        if (!query.isEmpty()) url+=(url.contains("?")?"&":"?")+query;
        try {
            byte[] payload=body==null ? null : mapper.writeValueAsBytes(body);
            if (payload!=null && payload.length>1_048_576) throw fail("请求体超过1 MiB限制");
            int maxBytes=limit(config,"responseMaxBytes",1_048_576);
            var response=GuardedHttp.request(config,method,url,headers,payload,timeout,maxBytes);
            JsonNode parsed;
            try { parsed=mapper.readTree(response.body()); }
            catch (Exception e) { throw new TransportException("INVALID_JSON", "外部响应不是有效 JSON；已发送的写操作结果需核实"); }
            if (parsed==null) throw new TransportException("INVALID_JSON", "外部响应为空，无法提取 JSON");
            JsonNode result=JsonPaths.read(parsed,config.path("responsePath").asText("$"));
            if (result.isMissingNode()) throw new TransportException("RESPONSE_PATH_MISSING", "响应提取失败：字段缺失");
            result=redact(result,credential);
            if (mapper.writeValueAsString(result).length()>limit(config,"resultMaxChars",32768) || mapper.writeValueAsBytes(result).length>32768) throw new TransportException("RESULT_TOO_LARGE", "工具结果超过模型输入限制");
            return result;
        } catch (java.io.IOException e) { throw fail("JSON 参数无法编码"); }
    }
    public static JsonNode redact(JsonNode node, String secret) {
        if (secret==null || secret.isEmpty()) return node.deepCopy();
        if (node.isTextual()) return TextNode.valueOf(node.textValue().replace(secret,"[REDACTED]"));
        if (node.isObject()) {
            ObjectNode result=JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> result.set(e.getKey().replace(secret,"[REDACTED]"),redact(e.getValue(),secret))); return result;
        }
        if (node.isArray()) { ArrayNode result=JsonNodeFactory.instance.arrayNode(); node.forEach(v -> result.add(redact(v,secret))); return result; }
        return node.deepCopy();
    }
    static int limit(JsonNode config,String key,int maximum) {
        int n=config.path(key).asInt(maximum);
        if (n<1 || n>maximum) throw fail(key+" 超出支持范围"); return n;
    }
    private static String scalar(JsonNode node) {
        if (node.isContainerNode() || node.isNull()) throw fail("路径、查询与 Header 参数必须是非空标量"); return node.asText();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+","%20"); }
    private static TransportException fail(String message) { return new TransportException("MAPPING_ERROR",message); }
}
