package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.time.Instant;

/** 一次运行里的单个步骤。 */
public class RunStep {
    private final int index;
    private final String name;
    private StepStatus status = StepStatus.PENDING;
    private Instant startedAt;
    private Instant finishedAt;
    private String failureReason;

    public RunStep(int index, String name) {
        this.index = index;
        this.name = name;
    }

    public int getIndex() { return index; }
    public String getName() { return name; }
    public StepStatus getStatus() { return status; }
    public void setStatus(StepStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
