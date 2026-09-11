package com.demo.cs.application.resources;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Deliberately bounded JSON Schema subset: unknown keywords are rejected at registration. */
public final class JsonSchemaGuard {
    private JsonSchemaGuard() {}
    private static final Set<String> KEYS=Set.of("$schema","$id","type","properties","required","additionalProperties","items","enum","description","title","default","minimum","maximum","minLength","maxLength","minItems","maxItems");
    public static void definition(JsonNode s) { definition(s,0); }
    private static void definition(JsonNode s,int depth) {
        if(depth>12 || !s.isObject()) throw new IllegalArgumentException("inputSchema 必须为有限深度的对象");
        s.fieldNames().forEachRemaining(k->{if(!KEYS.contains(k)) throw new IllegalArgumentException("inputSchema 包含不支持的约束: "+k);});
        for(String annotation:List.of("$schema","$id"))if(s.has(annotation)&&!s.get(annotation).isTextual())throw new IllegalArgumentException("Schema元注释必须为字符串"); // metadata only, never resolved or fetched
        String type=s.path("type").asText();
        if(!Set.of("object","array","string","number","integer","boolean","null").contains(type)) throw new IllegalArgumentException("inputSchema.type 不支持");
        if(s.has("properties")) {if(!s.get("properties").isObject()) throw new IllegalArgumentException("properties 必须为对象");s.get("properties").forEach(v->definition(v,depth+1));}
        if(s.has("items")) definition(s.get("items"),depth+1);
        if(s.has("required") && !s.get("required").isArray()) throw new IllegalArgumentException("required 必须为数组");
        if(s.has("required")) s.get("required").forEach(v->{if(!v.isTextual())throw new IllegalArgumentException("required 项必须为名称");});
        if(s.has("additionalProperties")&&!s.get("additionalProperties").isBoolean())throw new IllegalArgumentException("additionalProperties 必须为布尔值");
    }
    public static void arguments(JsonNode schema,JsonNode value) { check(schema,value,"arguments",0); }
    private static void check(JsonNode s,JsonNode v,String path,int depth) {
        if(depth>12 || v==null) fail(path,"缺少参数");
        boolean valid=switch(s.path("type").asText()) {
            case "object" -> v.isObject(); case "array" -> v.isArray(); case "string" -> v.isTextual();
            case "integer" -> v.isIntegralNumber(); case "number" -> v.isNumber(); case "boolean" -> v.isBoolean(); case "null" -> v.isNull(); default -> false;
        };
        if(!valid) fail(path,"类型不匹配");
        if(s.has("enum")) {boolean matched=false;for(JsonNode option:s.get("enum"))if(option.equals(v))matched=true;if(!matched)fail(path,"不在允许值内");}
        if(v.isObject()) {
            for(JsonNode required:s.path("required"))if(!v.has(required.asText()))fail(path+"."+required.asText(),"必填");
            v.fields().forEachRemaining(e->{JsonNode child=s.path("properties").get(e.getKey());if(child!=null)check(child,e.getValue(),path+"."+e.getKey(),depth+1);else if(!s.path("additionalProperties").asBoolean(false))fail(path,"含未授权字段");});
        }
        if(v.isArray()) {bounds(s,"minItems","maxItems",v.size(),path);if(s.has("items"))for(int i=0;i<v.size();i++)check(s.get("items"),v.get(i),path+"["+i+"]",depth+1);}
        if(v.isTextual())bounds(s,"minLength","maxLength",v.textValue().length(),path);
        if(v.isNumber())bounds(s,"minimum","maximum",v.doubleValue(),path);
    }
    private static void bounds(JsonNode s,String low,String high,double n,String path) {if(s.has(low)&&n<s.get(low).asDouble() || s.has(high)&&n>s.get(high).asDouble())fail(path,"超出约束范围");}
    private static void fail(String path,String message) {throw new IllegalArgumentException(path+": "+message);}
}
