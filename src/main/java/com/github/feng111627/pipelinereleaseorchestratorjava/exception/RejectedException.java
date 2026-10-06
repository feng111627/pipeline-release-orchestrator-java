package com.github.feng111627.pipelinereleaseorchestratorjava.exception;

import com.github.feng111627.pipelinereleaseorchestratorjava.model.RejectReason;

/** 业务规则拒绝：携带稳定的原因码与可读说明，所有拒绝都经此抛出。 */
public class RejectedException extends RuntimeException {
    private final RejectReason reason;

    public RejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
