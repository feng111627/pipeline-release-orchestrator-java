package com.github.feng111627.pipelinereleaseorchestratorjava.model;

/**
 * 批量交付规划中的单个环境环节：描述在该环境上会发生什么。
 *
 * @param environment 环境名
 * @param action      PROMOTE（会变更）或 SKIP（已是目标版本）
 * @param fromVersion 交付前该环境当前版本，可能为 null（从未部署）
 * @param toVersion   交付后该环境当前版本
 */
public record DeliveryStep(String environment,
                           DeliveryStepType action,
                           String fromVersion,
                           String toVersion) {
}
