package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次批量交付的结论记录。只有整体成功的交付才会留下记录，
 * 因此 status 恒为 SUCCEEDED；同 (流水线, 版本, 目标环境) 的重复提交收敛到同一条记录。
 */
public class DeliveryRecord {
    public static final String SUCCEEDED = "SUCCEEDED";

    private final String id;
    private final String pipelineId;
    private final String version;
    private final String targetEnvironment;
    private final String status = SUCCEEDED;
    private final List<DeliveryStep> steps;
    private final Instant createdAt;
    private final Instant completedAt;

    public DeliveryRecord(String id, String pipelineId, String version, String targetEnvironment,
                          List<DeliveryStep> steps, Instant createdAt, Instant completedAt) {
        this.id = id;
        this.pipelineId = pipelineId;
        this.version = version;
        this.targetEnvironment = targetEnvironment;
        this.steps = steps;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public String getId() { return id; }
    public String getPipelineId() { return pipelineId; }
    public String getVersion() { return version; }
    public String getTargetEnvironment() { return targetEnvironment; }
    public String getStatus() { return status; }
    public List<DeliveryStep> getSteps() { return steps; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
