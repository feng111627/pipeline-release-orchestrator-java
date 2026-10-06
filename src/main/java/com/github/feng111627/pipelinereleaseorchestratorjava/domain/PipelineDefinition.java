package com.github.feng111627.pipelinereleaseorchestratorjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 流水线定义的某个版本。版本一旦提交即不可变，历史永远保留。
 *
 * @param name       流水线名称
 * @param version    单调递增的版本号
 * @param stages     按顺序的环境列表（提升必须按此顺序）
 * @param submittedAt 提交时间
 */
public record PipelineDefinition(String name, int version, List<String> stages, Instant submittedAt) {
}
