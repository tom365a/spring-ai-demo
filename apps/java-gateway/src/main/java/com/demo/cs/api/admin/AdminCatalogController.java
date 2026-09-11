package com.demo.cs.api.admin;
import com.demo.cs.api.dto.ApiDtos.ApiEnvelope;
import com.demo.cs.application.resources.ResourceService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/catalog")
public class AdminCatalogController {
 private final ResourceService resources;
 public AdminCatalogController(ResourceService resources){this.resources=resources;}
 @GetMapping("/tools") public ApiEnvelope<?> tools(@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(Map.of("items",catalog("TOOL")));}
 @GetMapping("/skills") public ApiEnvelope<?> skills(@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(Map.of("items",catalog("SKILL")));}
 @GetMapping("/mcp-servers") public ApiEnvelope<?> mcp(@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(Map.of("items",catalog("MCP")));}
 @PostMapping("/mcp-servers/{id}/refresh") public ApiEnvelope<?> refresh(@PathVariable String id,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"editor");return ApiEnvelope.ok(resources.sync(id));}
 private List<Map<String,Object>> catalog(String kind){
 List<Map<String,Object>> result=new ArrayList<>();
 for(var item:resources.list(kind,null,null,null)){
 Map<String,Object> row=new LinkedHashMap<>(item);
 if(item.get("publishedVersion")!=null) {
 var s=resources.detail(item.get("id").toString());var p=(Map<?,?>)s.get("published");com.fasterxml.jackson.databind.JsonNode c=(com.fasterxml.jackson.databind.JsonNode)p.get("config");
 c.fields().forEachRemaining(e->{if(!e.getKey().equals("target")&&!e.getKey().equals("auth"))row.put(e.getKey(),e.getValue());});row.put("paramSchema",c.path("inputSchema"));row.put("ownerDomain",c.path("source").asText());row.put("endpoint",c.path("url").asText());row.put("status",Boolean.TRUE.equals(item.get("enabled"))?"UP":"DOWN");
 }
 result.add(row);
 }
 return result;
 }
}