package com.github.feng111627.pipelinereleaseorchestratorjava.domain;

/** 运行状态机：QUEUED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED */
public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
