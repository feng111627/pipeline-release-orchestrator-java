package com.github.feng111627.pipelinereleaseorchestratorjava.web;

import com.github.feng111627.pipelinereleaseorchestratorjava.exception.DeliveryPlanRejectedException;
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
        Map<String, Object> payload = body(ex.getReason().name(), ex.getMessage());
        if (ex instanceof DeliveryPlanRejectedException planEx) {
            // 批量交付整体被拒：额外指出被卡的环境，便于定位。
            java.util.Map<String, Object> withEnv = new java.util.LinkedHashMap<>(payload);
            withEnv.put("failureEnvironment", planEx.getFailureEnvironment());
            return ResponseEntity.status(statusOf(planEx.getReason())).body(withEnv);
        }
        return ResponseEntity.status(statusOf(ex.getReason())).body(payload);
    }

    /** 资源不存在 404，参数非法 400，其余业务规则拒绝 409。 */
    public static HttpStatus statusOf(RejectReason reason) {
        if (NOT_FOUND.contains(reason)) {
            return HttpStatus.NOT_FOUND;
        }
        if (BAD_REQUEST.contains(reason)) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.CONFLICT;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(body(RejectReason.INVALID_REQUEST.name(), "请求体无法解析: " + ex.getMessage()));
    }

    private static Map<String, Object> body(String reason, String message) {
        return Map.of(
                "rejected", true,
                "reason", reason,
                "message", message == null ? "" : message,
                "at", Instant.now().toString());
    }
}
