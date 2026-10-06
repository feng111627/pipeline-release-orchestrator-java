package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次流水线运行记录。步骤状态的推进全部可见，
 * 失败时可在 failedStep / failureReason 看到走到哪一步、因为什么失败。
 */
public class RunRecord {
    private final String id;
    private final String pipelineId;
    private final String version;
    /** 若是重试产生的运行，指向被重试的运行 id；否则为 null。 */
    private final String retryOfId;
    private final Integer failAtStep;
    private final Instant createdAt;
    private RunStatus status = RunStatus.PENDING;
    private final List<RunStep> steps = new ArrayList<>();
    private Instant finishedAt;
    private int failedStep = -1;
    private String failureReason;
    /** 取消标记，一旦置位，执行线程不得再产生任何成功结果。 */
    private volatile boolean cancelRequested = false;
    /** 暂停点：执行到该步骤开始前会阻塞，等待放行（测试用，保证确定性）。 */
    private volatile Integer pauseBeforeStep;
    private volatile Instant startedAt;

    public RunRecord(String id, String pipelineId, String version, String retryOfId,
                     Integer failAtStep, Integer pauseBeforeStep, Instant createdAt) {
        this.id = id;
        this.pipelineId = pipelineId;
        this.version = version;
        this.retryOfId = retryOfId;
        this.failAtStep = failAtStep;
        this.pauseBeforeStep = pauseBeforeStep;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getPipelineId() { return pipelineId; }
    public String getVersion() { return version; }
    public String getRetryOfId() { return retryOfId; }
    public Integer getFailAtStep() { return failAtStep; }
    public Instant getCreatedAt() { return createdAt; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public List<RunStep> getSteps() { return steps; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public int getFailedStep() { return failedStep; }
    public void setFailedStep(int failedStep) { this.failedStep = failedStep; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; }
    public Integer getPauseBeforeStep() { return pauseBeforeStep; }
    public void setPauseBeforeStep(Integer pauseBeforeStep) { this.pauseBeforeStep = pauseBeforeStep; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
}
