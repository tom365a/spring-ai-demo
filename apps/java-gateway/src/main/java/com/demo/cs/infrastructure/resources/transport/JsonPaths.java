package com.demo.cs.infrastructure.resources.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.*;

/** Deliberately small field-path grammar: $, foo.bar, items[0].id; no evaluation or wildcards. */
public final class JsonPaths {
    private JsonPaths() {}
    private static final Pattern PART = Pattern.compile("([\\p{L}_][\\p{L}\\p{N}_-]*)|\\[(\\d{1,4})\\]");
    private static List<Object> parts(String path) {
        if (path==null || path.isBlank()) throw fail("字段路径不能为空");
        if (path.equals("$")) return List.of();
        if (path.startsWith("$.")) path=path.substring(2);
        else if (path.startsWith("$[")) path=path.substring(1);
        List<Object> parts = new ArrayList<>(); int offset=0;
        while (offset<path.length()) {
            if (offset>0 && path.charAt(offset)=='.') {
                offset++;
                if (offset==path.length() || path.charAt(offset)=='[') throw fail("字段路径格式不正确");
            } else if (offset>0 && path.charAt(offset)!='[') throw fail("字段路径格式不正确");
            Matcher m=PART.matcher(path); m.region(offset,path.length());
            if (!m.lookingAt()) throw fail("字段路径格式不正确");
            Object part=m.group(1)!=null ? m.group(1) : Integer.valueOf(m.group(2));
            if (part instanceof Integer index && index>999) throw fail("数组索引不能超过999");
            parts.add(part); offset=m.end();
            if (parts.size()>20) throw fail("字段路径层级过深");
        }
        return parts;
    }
    public static JsonNode read(JsonNode root, String path) {
        JsonNode node=root;
        for (Object part:parts(path)) {
            if (node==null) return MissingNode.getInstance();
            node=part instanceof Integer index ? node.path(index) : node.path((String)part);
        }
        return node==null ? MissingNode.getInstance() : node;
    }
    public static JsonNode write(JsonNode root, String path, JsonNode value) {
        var parts=parts(path);
        if (parts.isEmpty()) return value.deepCopy();
        if (root==null) root=parts.getFirst() instanceof Integer ? JsonNodeFactory.instance.arrayNode() : JsonNodeFactory.instance.objectNode();
        JsonNode cursor=root;
        for (int i=0;i<parts.size();i++) {
            Object part=parts.get(i); boolean last=i==parts.size()-1;
            JsonNode current=part instanceof Integer n ? cursor.path(n) : cursor.path((String)part);
            if (last && !current.isMissingNode() && !current.isNull()) throw fail("重复的请求体映射目标");
            JsonNode next=last ? value.deepCopy() : current;
            if (!last && (next.isMissingNode() || next.isNull())) next=parts.get(i+1) instanceof Integer ? JsonNodeFactory.instance.arrayNode() : JsonNodeFactory.instance.objectNode();
            if (part instanceof Integer index && cursor instanceof ArrayNode arr) {
                while (arr.size()<=index) arr.addNull();
                arr.set(index,next);
            } else if (part instanceof String key && cursor instanceof ObjectNode obj) obj.set(key,next);
            else throw fail("请求体映射目标类型冲突");
            cursor=next;
        }
        return root;
    }
    private static TransportException fail(String message) { return new TransportException("MAPPING_ERROR",message); }
}
