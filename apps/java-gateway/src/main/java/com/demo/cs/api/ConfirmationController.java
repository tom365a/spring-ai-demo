package com.demo.cs.api;
import com.demo.cs.api.dto.ApiDtos.ApiEnvelope;
import com.demo.cs.application.resources.ManagedAgentRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/confirmations")
public class ConfirmationController {
 private final ManagedAgentRuntime runtime;
 public ConfirmationController(ManagedAgentRuntime runtime){this.runtime=runtime;}
 @PostMapping("/{id}") public ApiEnvelope<?> confirm(@PathVariable String id,@RequestBody JsonNode body){
 if(!body.isObject()||!body.path("sessionId").isTextual()||!body.path("confirm").isBoolean()||body.size()!=2)throw new IllegalArgumentException("确认请求仅接受sessionId和confirm");
 return ApiEnvelope.ok(runtime.confirm(id,body.get("sessionId").asText(),body.get("confirm").asBoolean()).toChatResponseMap());
 }
}
