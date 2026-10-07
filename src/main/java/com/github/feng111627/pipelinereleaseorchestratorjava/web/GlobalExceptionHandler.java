package com.github.feng111627.pipelinereleaseorchestratorjava.web;

import com.github.feng111627.pipelinereleaseorchestratorjava.exception.RejectedException;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RejectReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 统一错误响应：
 * <pre>{ "rejected": true, "reason": "VERSION_ALREADY_EXISTS", "message": "...", "at": "..." }</pre>
 * 资源不存在返回 404，参数非法返回 400，业务规则拒绝返回 409。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Set<RejectReason> NOT_FOUND =
            Set.of(RejectReason.PIPELINE_NOT_FOUND, RejectReason.VERSION_NOT_FOUND,
                    RejectReason.RUN_NOT_FOUND, RejectReason.DELIVERY_NOT_FOUND);

    private static final Set<RejectReason> BAD_REQUEST =
            Set.of(RejectReason.INVALID_REQUEST, RejectReason.INVALID_VERSION,
                    RejectReason.INVALID_ENVIRONMENT);

    @ExceptionHandler(RejectedException.class)
    public ResponseEntity<Map<String, Object>> handle(RejectedException ex) {
        RejectReason reason = ex.getReason();
        HttpStatus status;
        if (NOT_FOUND.contains(reason)) {
            status = HttpStatus.NOT_FOUND;
        } else if (BAD_REQUEST.contains(reason)) {
            status = HttpStatus.BAD_REQUEST;
        } else {
            status = HttpStatus.CONFLICT;
        }
        return ResponseEntity.status(status).body(body(reason.name(), ex.getMessage(), ex.getEnvironment()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(body(RejectReason.INVALID_REQUEST.name(), "请求体无法解析: " + ex.getMessage(), null));
    }

    private static Map<String, Object> body(String reason, String message, String environment) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("rejected", true);
        map.put("reason", reason);
        map.put("message", message == null ? "" : message);
        if (environment != null) {
            // 仅批量交付等需要定位「卡在哪个环境」的拒绝会带上该字段。
            map.put("environment", environment);
        }
        map.put("at", Instant.now().toString());
        return map;
    }
}
