package com.github.feng111627.pipelinereleaseorchestratorjava.dto;

/** 批量交付请求：把指定版本按固定环境顺序一路交付到目标环境。 */
public class DeliverRequest {
    /** 要交付的版本，必填。 */
    private String version;
    /** 目标环境，必填（DEV/TEST/STAGING/PROD）。 */
    private String targetEnvironment;
    /** true 表示只预演不落任何状态；默认 false。 */
    private boolean dryRun;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getTargetEnvironment() { return targetEnvironment; }
    public void setTargetEnvironment(String targetEnvironment) { this.targetEnvironment = targetEnvironment; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
}
