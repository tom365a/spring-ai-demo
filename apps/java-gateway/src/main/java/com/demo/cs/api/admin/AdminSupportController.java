package com.demo.cs.api.admin;

import com.demo.cs.api.dto.ApiDtos.ApiEnvelope;
import com.demo.cs.application.support.SupportConsoleService;
import org.springframework.web.bind.annotation.*;

/** 真人坐席控制台。回复由坐席本人输入，不经过模型。 */
@RestController
@RequestMapping("/api/v1/admin/support")
public class AdminSupportController {

    private final SupportConsoleService console;

    public AdminSupportController(SupportConsoleService console) {
        this.console = console;
    }

    public record Claim(String operatorId, String operatorName) {}
    public record Reply(String operatorId, String text) {}
    public record Close(String operatorId, String note) {}

    @GetMapping("/queue")
    public ApiEnvelope<?> queue(@RequestHeader(value = "X-Admin-Role", required = false) String role) {
        AdminAgentController.requireRole(role, "viewer");
        return ApiEnvelope.ok(console.queue());
    }

    @GetMapping("/{sessionId}")
    public ApiEnvelope<?> conversation(@PathVariable String sessionId,
                                       @RequestHeader(value = "X-Admin-Role", required = false) String role) {
        AdminAgentController.requireRole(role, "viewer");
        return ApiEnvelope.ok(console.conversation(sessionId));
    }

    @PostMapping("/{sessionId}/claim")
    public ApiEnvelope<?> claim(@PathVariable String sessionId, @RequestBody Claim body,
                               @RequestHeader(value = "X-Admin-Role", required = false) String role) {
        AdminAgentController.requireRole(role, "editor");
        return ApiEnvelope.ok(console.claim(sessionId, body.operatorId(), body.operatorName()));
    }

    @PostMapping("/{sessionId}/reply")
    public ApiEnvelope<?> reply(@PathVariable String sessionId, @RequestBody Reply body,
                               @RequestHeader(value = "X-Admin-Role", required = false) String role) {
        AdminAgentController.requireRole(role, "editor");
        return ApiEnvelope.ok(console.reply(sessionId, body.operatorId(), body.text()));
    }

    @PostMapping("/{sessionId}/close")
    public ApiEnvelope<?> close(@PathVariable String sessionId, @RequestBody Close body,
                               @RequestHeader(value = "X-Admin-Role", required = false) String role) {
        AdminAgentController.requireRole(role, "editor");
        return ApiEnvelope.ok(console.close(sessionId, body.operatorId(), body.note()));
    }
}
