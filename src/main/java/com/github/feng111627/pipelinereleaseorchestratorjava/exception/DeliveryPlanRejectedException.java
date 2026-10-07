package com.github.feng111627.pipelinereleaseorchestratorjava.exception;

import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeliveryStep;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RejectReason;

import java.util.List;

/**
 * 批量交付在规划阶段被整体拒绝：除原因码与说明外，还指出被卡的环境，
 * 以及走到该步之前已经规划出的环节（供预演展示）。
 * 规划阶段不修改任何状态，因此它不携带任何已写入结果。
 */
public class DeliveryPlanRejectedException extends RejectedException {
    private final String failureEnvironment;
    private final List<DeliveryStep> partialSteps;

    public DeliveryPlanRejectedException(String failureEnvironment, RejectReason reason,
                                         String message, List<DeliveryStep> partialSteps) {
        super(reason, message);
        this.failureEnvironment = failureEnvironment;
        this.partialSteps = partialSteps == null ? List.of() : List.copyOf(partialSteps);
    }

    public String getFailureEnvironment() { return failureEnvironment; }
    public List<DeliveryStep> getPartialSteps() { return partialSteps; }
}
