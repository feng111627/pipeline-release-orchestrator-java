package com.github.feng111627.pipelinereleaseorchestratorjava.dto;

/**
 * 批量交付请求：指定版本，沿 DEV -> ... -> targetEnvironment 的固定顺序一次性交付。
 */
public class BatchDeliveryRequest {
    /** 要交付的版本号（必填）。 */
    private String version;
    /** 目标环境（必填）：DEV / TEST / STAGING / PROD。 */
    private String targetEnvironment;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getTargetEnvironment() { return targetEnvironment; }
    public void setTargetEnvironment(String targetEnvironment) { this.targetEnvironment = targetEnvironment; }
}
