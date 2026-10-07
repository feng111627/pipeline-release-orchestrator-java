package com.github.feng111627.pipelinereleaseorchestratorjava.model;

/** 批量交付中单个环境环节的动作类型。 */
public enum DeliveryStepType {
    /** 该环境当前不是目标版本，本次会被提升到目标版本。 */
    PROMOTE,
    /** 该环境当前已经是目标版本，本次跳过、不产生新部署记录。 */
    SKIP
}
