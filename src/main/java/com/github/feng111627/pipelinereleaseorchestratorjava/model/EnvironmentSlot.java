package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.time.Instant;

/**
 * 某条流水线在某个环境上的槽位状态。
 * 环境顺序由服务层统一固定为 DEV -> TEST -> STAGING -> PROD。
 */
public class EnvironmentSlot {
    private final String pipelineId;
    private final String environment;
    /** 当前对外生效的版本；为空表示该环境从未部署过。 */
    private String currentVersion;
    private Instant currentAt;
    private DeploymentAction lastAction;
    /** 上一次生效的版本（用于展示回滚前的来源）。 */
    private String previousVersion;
    /** 最近一次被拒绝的原因（没有则为 null）。 */
    private RejectReason lastRejection;
    private String lastRejectionMessage;
    private Instant lastRejectionAt;
    /** 该环境上的全部部署记录（提升/回滚），只追加。 */
    private final java.util.List<DeploymentRecord> history = new java.util.ArrayList<>();

    public EnvironmentSlot(String pipelineId, String environment) {
        this.pipelineId = pipelineId;
        this.environment = environment;
    }

    public String getPipelineId() { return pipelineId; }
    public String getEnvironment() { return environment; }
    public String getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
    public Instant getCurrentAt() { return currentAt; }
    public void setCurrentAt(Instant currentAt) { this.currentAt = currentAt; }
    public DeploymentAction getLastAction() { return lastAction; }
    public void setLastAction(DeploymentAction lastAction) { this.lastAction = lastAction; }
    public String getPreviousVersion() { return previousVersion; }
    public void setPreviousVersion(String previousVersion) { this.previousVersion = previousVersion; }
    public RejectReason getLastRejection() { return lastRejection; }
    public void setLastRejection(RejectReason lastRejection) { this.lastRejection = lastRejection; }
    public String getLastRejectionMessage() { return lastRejectionMessage; }
    public void setLastRejectionMessage(String lastRejectionMessage) { this.lastRejectionMessage = lastRejectionMessage; }
    public Instant getLastRejectionAt() { return lastRejectionAt; }
    public void setLastRejectionAt(Instant lastRejectionAt) { this.lastRejectionAt = lastRejectionAt; }
    public java.util.List<DeploymentRecord> getHistory() { return history; }
}
