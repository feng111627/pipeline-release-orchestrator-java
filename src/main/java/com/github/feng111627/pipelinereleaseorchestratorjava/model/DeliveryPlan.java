package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 批量交付预演结论（dryRun 恒为 true）。
 * feasible=true 时 steps 给出每个环境 from -> to 的完整计划；
 * feasible=false 时 failedStep/failedEnvironment/reason/message 指出会卡在哪里、因为什么被拒，
 * steps 只包含在失败点之前已完成推演的步骤。预演绝不改动任何状态。
 */
public class DeliveryPlan {
    private final boolean dryRun = true;
    private final boolean feasible;
    private final String pipelineId;
    private final String version;
    private final String targetEnvironment;
    private final List<DeliveryStep> steps;
    private final Integer failedStep;
    private final String failedEnvironment;
    /** 拒绝原因码名称（RejectReason.name()）；可行时为 null。 */
    private final String reason;
    private final String message;
    private final Instant at;

    public DeliveryPlan(boolean feasible, String pipelineId, String version, String targetEnvironment,
                        List<DeliveryStep> steps, Integer failedStep, String failedEnvironment,
                        String reason, String message, Instant at) {
        this.feasible = feasible;
        this.pipelineId = pipelineId;
        this.version = version;
        this.targetEnvironment = targetEnvironment;
        this.steps = steps;
        this.failedStep = failedStep;
        this.failedEnvironment = failedEnvironment;
        this.reason = reason;
        this.message = message;
        this.at = at;
    }

    public boolean isDryRun() { return dryRun; }
    public boolean isFeasible() { return feasible; }
    public String getPipelineId() { return pipelineId; }
    public String getVersion() { return version; }
    public String getTargetEnvironment() { return targetEnvironment; }
    public List<DeliveryStep> getSteps() { return steps; }
    public Integer getFailedStep() { return failedStep; }
    public String getFailedEnvironment() { return failedEnvironment; }
    public String getReason() { return reason; }
    public String getMessage() { return message; }
    public Instant getAt() { return at; }
}
