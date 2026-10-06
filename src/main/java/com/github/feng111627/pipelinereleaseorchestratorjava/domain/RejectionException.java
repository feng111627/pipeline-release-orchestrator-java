package com.github.feng111627.pipelinereleaseorchestratorjava.domain;

/**
 * 业务拒绝异常：携带稳定的拒绝原因码，便于接口调用方区分场景。
 */
public class RejectionException extends RuntimeException {

    private final String reasonCode;

    public RejectionException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
