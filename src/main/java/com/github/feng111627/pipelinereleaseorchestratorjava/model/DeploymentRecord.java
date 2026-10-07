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
    /** 若该部署由批量交付产生，为对应交付 id；单环境提升/回滚为 null。 */
    private final String deliveryId;

    public DeploymentRecord(String id, String pipelineId, String environment, String version,
                            DeploymentAction action, String fromVersion, Instant at) {
        this(id, pipelineId, environment, version, action, fromVersion, at, null);
    }

    public DeploymentRecord(String id, String pipelineId, String environment, String version,
                            DeploymentAction action, String fromVersion, Instant at,
                            String deliveryId) {
        this.id = id;
        this.pipelineId = pipelineId;
        this.environment = environment;
        this.version = version;
        this.action = action;
        this.fromVersion = fromVersion;
        this.at = at;
        this.deliveryId = deliveryId;
    }

    public String getId() { return id; }
    public String getPipelineId() { return pipelineId; }
    public String getEnvironment() { return environment; }
    public String getVersion() { return version; }
    public DeploymentAction getAction() { return action; }
    public String getFromVersion() { return fromVersion; }
    public Instant getAt() { return at; }
    public String getDeliveryId() { return deliveryId; }
}
