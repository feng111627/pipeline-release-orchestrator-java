package com.github.feng111627.pipelinereleaseorchestratorjava.dto;

public class RollbackRequest {
    /** 从哪个环境回滚，必填。 */
    private String environment;

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
}
