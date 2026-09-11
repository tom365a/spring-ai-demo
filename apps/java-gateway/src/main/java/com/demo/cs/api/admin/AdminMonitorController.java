package com.demo.cs.api.admin;
import com.demo.cs.api.dto.ApiDtos.ApiEnvelope;
import com.demo.cs.application.session.ConversationMonitor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/monitor/sessions")
public class AdminMonitorController {
 private final ConversationMonitor monitor;
 public AdminMonitorController(ConversationMonitor monitor){this.monitor=monitor;}
 @GetMapping public ApiEnvelope<?> list(@RequestParam(required=false)String q,@RequestParam(required=false)String status,@RequestParam(required=false)String channel,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(monitor.list(q,status,channel,page,size));}
 @GetMapping("/{id}") public ApiEnvelope<?> detail(@PathVariable String id,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"viewer");return ApiEnvelope.ok(monitor.detail(id));}
 public record Assessment(String status,String note){}
 @PostMapping("/{id}/assessment") public ApiEnvelope<?> assess(@PathVariable String id,@RequestBody Assessment body,@RequestHeader(value="X-Admin-Role",required=false)String role){AdminAgentController.requireRole(role,"editor");return ApiEnvelope.ok(monitor.assess(id,body.status(),body.note()));}
}
