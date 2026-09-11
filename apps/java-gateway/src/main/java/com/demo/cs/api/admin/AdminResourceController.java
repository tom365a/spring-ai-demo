package com.demo.cs.api.admin;
import com.demo.cs.api.dto.ApiDtos.ApiEnvelope;
import com.demo.cs.application.resources.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/resources")
public class AdminResourceController {
 private final ResourceService resources;private final ManagedAgentRuntime runtime;private final ObjectMapper json;
 public AdminResourceController(ResourceService resources,ManagedAgentRuntime runtime,ObjectMapper json){this.resources=resources;this.runtime=runtime;this.json=json;}
 @GetMapping public ApiEnvelope<?> list(@RequestParam(required=false)String kind,@RequestParam(required=false)String q,@RequestParam(required=false)Boolean enabled,@RequestParam(required=false)String source,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(Map.of("items",resources.list(kind,q,enabled,source)));}
 @GetMapping("/{id}") public ApiEnvelope<?> detail(@PathVariable String id,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(resources.detail(id));}
 @PostMapping public ApiEnvelope<?> create(@RequestBody JsonNode body,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"editor");return ApiEnvelope.ok(resources.save(null,body,publisher(role)));}
 @PutMapping("/{id}") public ApiEnvelope<?> save(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"editor");return ApiEnvelope.ok(resources.save(id,body,publisher(role)));}
 @GetMapping("/{id}/impact") public ApiEnvelope<?> impact(@PathVariable String id,@RequestParam String action,@RequestParam(required=false)Integer version,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(resources.impact(id,action,version));}
 @PostMapping("/{id}/{action:publish|enable|disable|default|rollback}") public ApiEnvelope<?> lifecycle(@PathVariable String id,@PathVariable String action,@RequestBody JsonNode body,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"publisher");return ApiEnvelope.ok(resources.lifecycle(id,action,body));}
 @DeleteMapping("/{id}") public ApiEnvelope<?> delete(@PathVariable String id,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"publisher");resources.delete(id);return ApiEnvelope.ok(Map.of("deleted",true));}
 @GetMapping("/{id}/references") public ApiEnvelope<?> references(@PathVariable String id,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(Map.of("items",resources.references(id)));}
 @GetMapping("/{id}/versions") public ApiEnvelope<?> versions(@PathVariable String id,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(Map.of("items",resources.history(id),"audit",resources.auditHistory(id)));}
 @PostMapping("/{id}/sync") public ApiEnvelope<?> sync(@PathVariable String id,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"editor");return ApiEnvelope.ok(resources.sync(id));}
 @PostMapping("/{id}/test") public ApiEnvelope<?> test(@PathVariable String id,@RequestBody(required=false)JsonNode body,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"editor");return ApiEnvelope.ok(runtime.test(id,body!=null&&body.has("arguments")?body.get("arguments"):json.createObjectNode(),publisher(role)));}
 private boolean publisher(String role){return "publisher".equalsIgnoreCase(role)||"admin".equalsIgnoreCase(role);}
}
