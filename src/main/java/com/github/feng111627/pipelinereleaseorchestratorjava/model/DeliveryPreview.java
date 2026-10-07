package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 批量交付预演结果。预演绝不改动任何状态：
 * <ul>
 *   <li>{@code deliverable=true}：{@link #steps} 给出每个环境 from -> to；</li>
 *   <li>{@code deliverable=false}：{@link #failureEnvironment} 与 {@link #reason}
 *       指出会卡在哪个环境、因为什么被拒，{@link #steps} 为走到该步之前规划出的环节。</li>
 * </ul>
 */
public class DeliveryPreview {
    private final boolean dryRun = true;
    private boolean deliverable;
    private String pipelineId;
    private String version;
    private String targetEnvironment;
    private List<DeliveryStep> steps;
    private String failureEnvironment;
    private String reason;
    private String message;
    private Instant at;

    public DeliveryPreview() {
    }

    public static DeliveryPreview ok(String pipelineId, String version, String targetEnvironment,
                                     List<DeliveryStep> steps) {
        DeliveryPreview p = new DeliveryPreview();
        p.deliverable = true;
        p.pipelineId = pipelineId;
        p.version = version;
        p.targetEnvironment = targetEnvironment;
        p.steps = List.copyOf(steps);
        p.at = Instant.now();
        return p;
    }

    public static DeliveryPreview rejected(String pipelineId, String version, String targetEnvironment,
                                           List<DeliveryStep> steps, String failureEnvironment,
                                           RejectReason reason, String message) {
        DeliveryPreview p = new DeliveryPreview();
        p.deliverable = false;
        p.pipelineId = pipelineId;
        p.version = version;
        p.targetEnvironment = targetEnvironment;
        p.steps = steps == null ? List.of() : List.copyOf(steps);
        p.failureEnvironment = failureEnvironment;
        p.reason = reason.name();
        p.message = message;
        p.at = Instant.now();
        return p;
    }

    public boolean isDryRun() { return dryRun; }
    public boolean isDeliverable() { return deliverable; }
    public String getPipelineId() { return pipelineId; }
    public String getVersion() { return version; }
    public String getTargetEnvironment() { return targetEnvironment; }
    public List<DeliveryStep> getSteps() { return steps; }
    public String getFailureEnvironment() { return failureEnvironment; }
    public String getReason() { return reason; }
    public String getMessage() { return message; }
    public Instant getAt() { return at; }
}
