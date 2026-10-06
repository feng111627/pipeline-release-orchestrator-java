package com.github.feng111627.pipelinereleaseorchestratorjava.service;

import com.github.feng111627.pipelinereleaseorchestratorjava.domain.EnvironmentState;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.PipelineDefinition;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.RejectionException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 环境提升与回滚。
 *
 * 提升规则：
 * - 版本必须已注册，否则 VERSION_NOT_FOUND；
 * - 目标环境必须在该版本定义的环境序列里，否则 UNKNOWN_ENVIRONMENT；
 * - 同一环境内版本必须向前，否则 ENV_VERSION_NOT_FORWARD；
 * - 非首个环境的前置环境当前必须已经部署同一版本，否则 STAGE_SKIPPED；
 * 任一规则不满足都直接拒绝，环境状态保持不变（无中间态）。
 *
 * 回滚规则：
 * - 回滚到该环境部署历史中最近一次不同于当前版本的版本；
 * - 没有可回滚目标时拒绝 NO_ROLLBACK_TARGET。
 */
@Service
public class EnvironmentService {

    private final PipelineRegistryService registry;
    private final Map<String, EnvironmentState> environments = new ConcurrentHashMap<>();
    private final Map<String, Object> pipelineLocks = new ConcurrentHashMap<>();

    public EnvironmentService(PipelineRegistryService registry) {
        this.registry = registry;
    }

    public EnvironmentState promote(String pipeline, int version, String environment) {
        PipelineDefinition definition = registry.requireVersion(pipeline, version);
        int stageIndex = definition.stages().indexOf(environment);
        if (stageIndex < 0) {
            throw new RejectionException("UNKNOWN_ENVIRONMENT",
                    "environment '%s' is not a stage of pipeline '%s' version %d"
                            .formatted(environment, pipeline, version));
        }
        synchronized (lockFor(pipeline)) {
            EnvironmentState target = stateFor(pipeline, environment);
            Integer current = target.getCurrentVersion();
            if (current != null && version <= current) {
                throw new RejectionException("ENV_VERSION_NOT_FORWARD",
                        "environment '%s' already at version %d; cannot promote version %d"
                                .formatted(environment, current, version));
            }
            if (stageIndex > 0) {
                String previousEnv = definition.stages().get(stageIndex - 1);
                Integer previousVersion = stateFor(pipeline, previousEnv).getCurrentVersion();
                if (previousVersion == null || previousVersion != version) {
                    throw new RejectionException("STAGE_SKIPPED",
                            "cannot promote version %d to '%s': previous stage '%s' is at version %s"
                                    .formatted(version, environment, previousEnv,
                                            previousVersion == null ? "none" : previousVersion));
                }
            }
            target.deploy(version, "PROMOTE");
            return target;
        }
    }

    public EnvironmentState rollback(String pipeline, String environment) {
        synchronized (lockFor(pipeline)) {
            EnvironmentState state = environments.get(key(pipeline, environment));
            if (state == null) {
                throw new RejectionException("UNKNOWN_ENVIRONMENT",
                        "pipeline '%s' has no deployments in environment '%s'".formatted(pipeline, environment));
            }
            Integer current = state.getCurrentVersion();
            // 从最新历史往前找第一个不同于当前版本的版本
            List<EnvironmentState.DeploymentEntry> history = state.getHistory();
            Integer target = null;
            for (int i = history.size() - 1; i >= 0; i--) {
                int v = history.get(i).version();
                if (current == null || v != current) {
                    target = v;
                    break;
                }
            }
            if (target == null) {
                throw new RejectionException("NO_ROLLBACK_TARGET",
                        "environment '%s' of pipeline '%s' has no previous version to roll back to"
                                .formatted(environment, pipeline));
            }
            state.deploy(target, "ROLLBACK");
            return state;
        }
    }

    public List<EnvironmentState> listEnvironments(String pipeline) {
        return environments.values().stream()
                .filter(e -> e.getPipelineName().equals(pipeline))
                .sorted((a, b) -> a.getEnvironment().compareTo(b.getEnvironment()))
                .toList();
    }

    private EnvironmentState stateFor(String pipeline, String environment) {
        return environments.computeIfAbsent(key(pipeline, environment),
                k -> new EnvironmentState(pipeline, environment));
    }

    private Object lockFor(String pipeline) {
        return pipelineLocks.computeIfAbsent(pipeline, k -> new Object());
    }

    private static String key(String pipeline, String environment) {
        return pipeline + "@" + environment;
    }
}
