package com.github.feng111627.pipelinereleaseorchestratorjava.model;

/** 运行中单个步骤的状态。 */
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
