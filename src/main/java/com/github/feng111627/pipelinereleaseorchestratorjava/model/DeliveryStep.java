package com.github.feng111627.pipelinereleaseorchestratorjava.model;

/**
 * 批量交付计划中的一步：某环境在本次交付中从哪个版本变成哪个版本。
 * 已经是目标版本的环境对应 {@code changed=false} 的收敛步，不产生部署记录。
 */
public class DeliveryStep {
    /** 从 0 开始的步骤序号，与环境固定顺序一致。 */
    private final int step;
    private final String environment;
    /** 该环境当前版本；从未部署时为 null。 */
    private final String fromVersion;
    private final String toVersion;
    /** 该步是否真的会改写环境；false 表示已是目标版本的收敛空操作。 */
    private final boolean changed;
    /** 提交生效后对应的部署记录 id；预演或收敛步为 null。 */
    private String deploymentId;

    public DeliveryStep(int step, String environment, String fromVersion,
                        String toVersion, boolean changed) {
        this.step = step;
        this.environment = environment;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.changed = changed;
    }

    public int getStep() { return step; }
    public String getEnvironment() { return environment; }
    public String getFromVersion() { return fromVersion; }
    public String getToVersion() { return toVersion; }
    public boolean isChanged() { return changed; }
    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
}
