package com.demo.cs.api;

import com.demo.cs.application.agentconfig.AgentValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformed(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(40001,"请求JSON格式或字段类型不正确"));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> duplicate(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(err(40901,"数据冲突，请检查标识是否已存在并刷新后重试"));
    }

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

    /** 静态资源缺失（favicon 等）是 404，不该走 500 兜底，也不该打堆栈把日志刷脏。 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> missingResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(40401, "资源不存在：" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        Map<String, Object> body = err(50001, "服务处理失败，请根据traceId排查服务端状态");
        // The response tells the caller to look the traceId up, so it has to actually be in the log.
        log.error("Unhandled request failure traceId={}", body.get("traceId"), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
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
