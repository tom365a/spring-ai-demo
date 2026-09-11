package com.demo.cs.api;
import com.demo.cs.api.dto.ApiDtos.ApiEnvelope;
import com.demo.cs.application.support.HumanSupportService;
import com.demo.cs.application.support.SupportConsoleService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/sessions/{id}/handoff")
public class HumanSupportController {
 private final HumanSupportService support;private final SupportConsoleService console;
 public HumanSupportController(HumanSupportService support,SupportConsoleService console){this.support=support;this.console=console;}
 public record Request(String userId){}
 @PostMapping public ApiEnvelope<?> transfer(@PathVariable String id,@RequestBody Request body){return ApiEnvelope.ok(support.transfer(id,body.userId()));}
 @GetMapping public ApiEnvelope<?> state(@PathVariable String id,@RequestParam String userId){return ApiEnvelope.ok(support.state(id,userId));}
 /** 客户端轮询：坐席回复后在这里取回新消息。 */
 @GetMapping("/messages") public ApiEnvelope<?> messages(@PathVariable String id,@RequestParam String userId){return ApiEnvelope.ok(console.customerView(id,userId));}
}
