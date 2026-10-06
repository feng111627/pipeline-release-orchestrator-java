package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.time.Instant;

/** 流水线的一个不可变版本快照；提交后历史结论不可覆盖。 */
public class PipelineVersion {
    private final String pipelineId;
    private final String version;
    private final String commitRef;
    private final Instant submittedAt;
    private VersionStatus status;

    public PipelineVersion(String pipelineId, String version, String commitRef,
                           Instant submittedAt, VersionStatus status) {
        this.pipelineId = pipelineId;
        this.version = version;
        this.commitRef = commitRef;
        this.submittedAt = submittedAt;
        this.status = status;
    }

    public String getPipelineId() { return pipelineId; }
    public String getVersion() { return version; }
    public String getCommitRef() { return commitRef; }
    public Instant getSubmittedAt() { return submittedAt; }
    public VersionStatus getStatus() { return status; }
    public void setStatus(VersionStatus status) { this.status = status; }
}
