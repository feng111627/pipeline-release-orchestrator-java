package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.time.Instant;

/** 环境部署历史中的一条不可变记录（提升或回滚）。 */
public class DeploymentRecord {
    private final String id;
    private final String pipelineId;
    private final String environment;
    private final String version;
    private final DeploymentAction action;
    /** 回滚时，被替换掉的版本；提升时为 null。 */
    private final String fromVersion;
    private final Instant at;

    public DeploymentRecord(String id, String pipelineId, String environment, String version,
                            DeploymentAction action, String fromVersion, Instant at) {
        this.id = id;
        this.pipelineId = pipelineId;
        this.environment = environment;
        this.version = version;
        this.action = action;
        this.fromVersion = fromVersion;
        this.at = at;
    }

    public String getId() { return id; }
    public String getPipelineId() { return pipelineId; }
    public String getEnvironment() { return environment; }
    public String getVersion() { return version; }
    public DeploymentAction getAction() { return action; }
    public String getFromVersion() { return fromVersion; }
    public Instant getAt() { return at; }
}
