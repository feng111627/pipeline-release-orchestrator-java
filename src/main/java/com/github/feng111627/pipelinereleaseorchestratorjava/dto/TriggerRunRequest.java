package com.github.feng111627.pipelinereleaseorchestratorjava.dto;

public class TriggerRunRequest {
    /** 要运行的版本，必填（必须属于该流水线）。 */
    private String version;
    /** 模拟在第几个步骤失败（从 0 开始）；可空表示全部成功。 */
    private Integer failAtStep;
    /** 测试用：在该步骤开始前暂停，等待 /resume 放行。 */
    private Integer pauseBeforeStep;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public Integer getFailAtStep() { return failAtStep; }
    public void setFailAtStep(Integer failAtStep) { this.failAtStep = failAtStep; }
    public Integer getPauseBeforeStep() { return pauseBeforeStep; }
    public void setPauseBeforeStep(Integer pauseBeforeStep) { this.pauseBeforeStep = pauseBeforeStep; }
}
