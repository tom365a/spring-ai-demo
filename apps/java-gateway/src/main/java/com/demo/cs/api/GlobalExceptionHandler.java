package com.demo.cs.api;

import com.demo.cs.application.agentconfig.AgentValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(err(40901, e.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SecurityException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(40301, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(40001, e.getMessage()));
    }

    @ExceptionHandler(AgentValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(AgentValidationException e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", false);
        data.put("errors", e.getErrors());
        data.put("warnings", e.getWarnings());
        Map<String, Object> body = err(42201, e.getMessage() != null ? e.getMessage() : "agent validation failed");
        body.put("data", data);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err(50001, e.getMessage()));
    }

    private Map<String, Object> err(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message != null ? message : "error");
        body.put("traceId", "tr_" + UUID.randomUUID().toString().substring(0, 8));
        body.put("data", null);
        return body;
    }
}
