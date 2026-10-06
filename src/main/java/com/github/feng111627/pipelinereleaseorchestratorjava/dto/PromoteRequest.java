package com.github.feng111627.pipelinereleaseorchestratorjava.dto;

public class PromoteRequest {
    /** 要提升的版本；为空时由服务层推导。 */
    private String version;
    /** 目标环境；为空时推导为当前所在环境的下一环境（首提升默认 DEV）。 */
    private String targetEnvironment;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getTargetEnvironment() { return targetEnvironment; }
    public void setTargetEnvironment(String targetEnvironment) { this.targetEnvironment = targetEnvironment; }
}
