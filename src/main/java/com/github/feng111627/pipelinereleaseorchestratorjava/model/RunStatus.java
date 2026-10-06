package com.github.feng111627.pipelinereleaseorchestratorjava.model;

/** 一次流水线运行的生命周期状态。 */
public enum RunStatus {
    /** 已创建，等待被调度/在暂停点等待放行。 */
    PENDING,
    /** 正在执行步骤。 */
    RUNNING,
    /** 所有步骤成功完成（终态）。 */
    SUCCEEDED,
    /** 某一步失败（终态）。 */
    FAILED,
    /** 被取消（终态）。 */
    CANCELLED
}
