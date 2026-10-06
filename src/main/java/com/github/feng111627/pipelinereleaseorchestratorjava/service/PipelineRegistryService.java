package com.github.feng111627.pipelinereleaseorchestratorjava.service;

import com.github.feng111627.pipelinereleaseorchestratorjava.domain.PipelineDefinition;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.RejectionException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流水线版本注册表：版本化提交、历史保留、重复/回退拒绝。
 *
 * 规则：
 * - 版本号必须严格大于当前最新版本；
 * - 等于已有版本 -> VERSION_ALREADY_EXISTS；
 * - 小于最新版本 -> VERSION_REGRESSION；
 * - 已提交的历史永不修改、永不删除。
 */
@Service
public class PipelineRegistryService {

    private final Map<String, List<PipelineDefinition>> definitions = new ConcurrentHashMap<>();

    public PipelineDefinition submitVersion(String name, int version, List<String> stages) {
        if (stages == null || stages.isEmpty()) {
            throw new RejectionException("EMPTY_STAGES",
                    "pipeline '%s' version %d must declare at least one stage".formatted(name, version));
        }
        // 对单条流水线的版本列表加锁，保证并发提交时版本检查与追加是原子的
        List<PipelineDefinition> history = definitions.computeIfAbsent(name, k -> new ArrayList<>());
        synchronized (history) {
            int latest = history.stream().mapToInt(PipelineDefinition::version).max().orElse(0);
            boolean exists = history.stream().anyMatch(d -> d.version() == version);
            if (exists) {
                throw new RejectionException("VERSION_ALREADY_EXISTS",
                        "pipeline '%s' version %d already exists; history cannot be overwritten".formatted(name, version));
            }
            if (version < latest) {
                throw new RejectionException("VERSION_REGRESSION",
                        "pipeline '%s' version %d is older than latest version %d".formatted(name, version, latest));
            }
            PipelineDefinition definition = new PipelineDefinition(name, version, List.copyOf(stages), Instant.now());
            history.add(definition);
            return definition;
        }
    }

    public List<PipelineDefinition> listVersions(String name) {
        List<PipelineDefinition> history = definitions.getOrDefault(name, List.of());
        synchronized (history) {
            return history.stream()
                    .sorted(Comparator.comparingInt(PipelineDefinition::version))
                    .toList();
        }
    }

    public PipelineDefinition getVersion(String name, int version) {
        List<PipelineDefinition> history = definitions.get(name);
        if (history == null) {
            return null;
        }
        synchronized (history) {
            return history.stream().filter(d -> d.version() == version).findFirst().orElse(null);
        }
    }

    public PipelineDefinition requireVersion(String name, int version) {
        PipelineDefinition definition = getVersion(name, version);
        if (definition == null) {
            throw new RejectionException("VERSION_NOT_FOUND",
                    "pipeline '%s' has no version %d".formatted(name, version));
        }
        return definition;
    }
}
