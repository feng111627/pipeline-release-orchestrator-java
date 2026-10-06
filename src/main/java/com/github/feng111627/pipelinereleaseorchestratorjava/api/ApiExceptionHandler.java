package com.github.feng111627.pipelinereleaseorchestratorjava.api;

import com.github.feng111627.pipelinereleaseorchestratorjava.domain.RejectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 把业务拒绝统一映射为 409 + 原因码，保证拒绝原因可通过接口查询。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorBody(String reasonCode, String message, Instant at) {
    }

    @ExceptionHandler(RejectionException.class)
    public ResponseEntity<ErrorBody> handleRejection(RejectionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody(ex.getReasonCode(), ex.getMessage(), Instant.now()));
    }
}
