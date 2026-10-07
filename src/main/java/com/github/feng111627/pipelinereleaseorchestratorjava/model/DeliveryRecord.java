package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次「批量交付」的结果记录。幂等键为 (pipelineId, version, targetEnvironment)：
 * 同一键只可能有一条真正生效的交付，重复/并发提交都会收敛到这条记录
 * （通过 {@link #withReused(boolean)} 得到 reused=true 的同内容视图）。
 */
public class DeliveryRecord {
    private final String id;
    private final String pipelineId;
    private final String version;
    private final String targetEnvironment;
    /** 交付经过的各环境环节（含被跳过的环节），顺序即执行顺序。 */
    private final List<DeliveryStep> steps;
    /** 本次实际产生的部署记录 id（SKIP 环节不计入），顺序即执行顺序。 */
    private final List<String> deploymentIds;
    private final Instant createdAt;
    /** true 表示本次响应是复用了此前已生效的同键交付，没有再次执行。 */
    private final boolean reused;

    public DeliveryRecord(String id, String pipelineId, String version, String targetEnvironment,
                          List<DeliveryStep> steps, List<String> deploymentIds, Instant createdAt) {
        this(id, pipelineId, version, targetEnvironment, steps, deploymentIds, createdAt, false);
    }

    private DeliveryRecord(String id, String pipelineId, String version, String targetEnvironment,
                           List<DeliveryStep> steps, List<String> deploymentIds, Instant createdAt,
                           boolean reused) {
        this.id = id;
        this.pipelineId = pipelineId;
        this.version = version;
        this.targetEnvironment = targetEnvironment;
        this.steps = List.copyOf(steps);
        this.deploymentIds = List.copyOf(deploymentIds);
        this.createdAt = createdAt;
        this.reused = reused;
    }

    /** 返回内容相同、但 reused 标记为指定值的视图，不触碰已生效记录本身。 */
    public DeliveryRecord withReused(boolean value) {
        return new DeliveryRecord(id, pipelineId, version, targetEnvironment, steps,
                deploymentIds, createdAt, value);
    }

    public String getId() { return id; }
    public String getPipelineId() { return pipelineId; }
    public String getVersion() { return version; }
    public String getTargetEnvironment() { return targetEnvironment; }
    public List<DeliveryStep> getSteps() { return steps; }
    public List<String> getDeploymentIds() { return deploymentIds; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isReused() { return reused; }
}
