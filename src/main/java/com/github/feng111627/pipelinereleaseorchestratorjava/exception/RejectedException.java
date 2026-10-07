package com.github.feng111627.pipelinereleaseorchestratorjava.exception;

import com.github.feng111627.pipelinereleaseorchestratorjava.model.RejectReason;

/**
 * 业务规则拒绝：携带稳定的原因码与可读说明，所有拒绝都经此抛出。
 * 批量交付的拒绝还可携带 {@code environment}，指出整体卡在哪一个环境。
 */
public class RejectedException extends RuntimeException {
    private final RejectReason reason;
    private final String environment;

    public RejectedException(RejectReason reason, String message) {
        this(reason, null, message);
    }

    public RejectedException(RejectReason reason, String environment, String message) {
        super(message);
        this.reason = reason;
        this.environment = environment;
    }

    public RejectReason getReason() { return reason; }
    public String getEnvironment() { return environment; }
}
