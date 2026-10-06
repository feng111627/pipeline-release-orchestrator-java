package com.github.feng111627.pipelinereleaseorchestratorjava.domain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 某条流水线在某个环境上的状态：当前版本 + 部署历史（用于回滚）。
 */
public class EnvironmentState {

    /** 一条部署历史：版本 + 动作（PROMOTE/ROLLBACK） */
    public record DeploymentEntry(int version, String action, java.time.Instant at) {
    }

    private final String pipelineName;
    private final String environment;
    private volatile Integer currentVersion;
    private final List<DeploymentEntry> history = new CopyOnWriteArrayList<>();

    public EnvironmentState(String pipelineName, String environment) {
        this.pipelineName = pipelineName;
        this.environment = environment;
    }

    public String getPipelineName() { return pipelineName; }
    public String getEnvironment() { return environment; }
    public Integer getCurrentVersion() { return currentVersion; }
    public List<DeploymentEntry> getHistory() { return history; }

    public void deploy(int version, String action) {
        this.currentVersion = version;
        this.history.add(new DeploymentEntry(version, action, java.time.Instant.now()));
    }
}
