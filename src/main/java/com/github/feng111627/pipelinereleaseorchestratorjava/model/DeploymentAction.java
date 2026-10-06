package com.github.feng111627.pipelinereleaseorchestratorjava.model;

/** 环境部署历史记录的动作类型。 */
public enum DeploymentAction {
    /** 版本提升到某环境。 */
    PROMOTE,
    /** 回滚到历史可用版本。 */
    ROLLBACK
}
