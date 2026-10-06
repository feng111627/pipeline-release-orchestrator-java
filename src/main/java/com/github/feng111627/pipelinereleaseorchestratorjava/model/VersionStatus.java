package com.github.feng111627.pipelinereleaseorchestratorjava.model;

/** 流水线版本的可用状态。 */
public enum VersionStatus {
    /** 可用于环境提升。 */
    AVAILABLE,
    /** 已被标记为不可用（隔离），禁止提升/作为回滚目标。 */
    BLOCKED
}
