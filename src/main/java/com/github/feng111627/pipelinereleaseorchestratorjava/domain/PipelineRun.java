package com.github.feng111627.pipelinereleaseorchestratorjava.domain;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次流水线运行记录。状态与步骤日志对并发读写安全。
 */
public class PipelineRun {

    /** 单步执行记录：步骤名 + 结果说明 */
    public record StepRecord(String stage, String outcome, Instant at) {
    }

    private final String id;
    private final String pipelineName;
    private final int version;
    private final String idempotencyKey;
    private final String failAtStage;
    private final Instant createdAt;
    private final AtomicReference<RunStatus> status = new AtomicReference<>(RunStatus.QUEUED);
    private final List<StepRecord> steps = new CopyOnWriteArrayList<>();
    private volatile String failureReason;
    private volatile String currentStage;

    public PipelineRun(String id, String pipelineName, int version, String idempotencyKey, String failAtStage) {
        this.id = id;
        this.pipelineName = pipelineName;
        this.version = version;
        this.idempotencyKey = idempotencyKey;
        this.failAtStage = failAtStage;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getPipelineName() { return pipelineName; }
    public int getVersion() { return version; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getFailAtStage() { return failAtStage; }
    public Instant getCreatedAt() { return createdAt; }
    public RunStatus getStatus() { return status.get(); }
    public List<StepRecord> getSteps() { return steps; }
    public String getFailureReason() { return failureReason; }
    public String getCurrentStage() { return currentStage; }

    /**
     * 状态机：QUEUED -> RUNNING -> SUCCEEDED/FAILED/CANCELLED；QUEUED 也可直接 CANCELLED。
     * 终态不可再迁移，CAS 保证并发下只有一个迁移获胜。
     */
    public boolean transitionTo(RunStatus next) {
        while (true) {
            RunStatus current = status.get();
            boolean allowed = switch (current) {
                case QUEUED -> next == RunStatus.RUNNING || next == RunStatus.CANCELLED;
                case RUNNING -> next == RunStatus.SUCCEEDED || next == RunStatus.FAILED || next == RunStatus.CANCELLED;
                case SUCCEEDED, FAILED, CANCELLED -> false;
            };
            if (!allowed) {
                return false;
            }
            if (status.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    public void addStep(String stage, String outcome) {
        this.currentStage = stage;
        this.steps.add(new StepRecord(stage, outcome, Instant.now()));
    }

    public void fail(String reason) {
        this.failureReason = reason;
        transitionTo(RunStatus.FAILED);
    }
}
