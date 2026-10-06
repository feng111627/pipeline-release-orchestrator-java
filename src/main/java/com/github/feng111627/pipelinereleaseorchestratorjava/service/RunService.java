package com.github.feng111627.pipelinereleaseorchestratorjava.service;

import com.github.feng111627.pipelinereleaseorchestratorjava.domain.PipelineDefinition;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.PipelineRun;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.RejectionException;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.RunStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 运行触发、取消、失败重试。
 *
 * - 触发：按 (流水线, 幂等键) 去重，相同键并发触发只产生一条运行；
 * - 执行：异步逐 stage 推进，每步落一条步骤记录；取消后不再产生新步骤、状态不可再变；
 * - 取消：仅 QUEUED/RUNNING 可取消，终态取消被拒绝；
 * - 重试：仅 FAILED 可重试；对同一条运行的重试幂等，重复重试返回同一条新运行。
 */
@Service
public class RunService {

    /** 每个 stage 的模拟执行耗时，给取消留出窗口 */
    public static final long STEP_DELAY_MS = 50;

    private final PipelineRegistryService registry;
    private final Map<String, PipelineRun> runs = new ConcurrentHashMap<>();
    private final Map<String, String> runIdByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, String> retryRunIdByOriginRunId = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RunService(PipelineRegistryService registry) {
        this.registry = registry;
    }

    public PipelineRun trigger(String pipeline, int version, String idempotencyKey, String failAtStage) {
        PipelineDefinition definition = registry.requireVersion(pipeline, version);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new RejectionException("MISSING_IDEMPOTENCY_KEY",
                    "triggering pipeline '%s' requires a non-blank idempotencyKey".formatted(pipeline));
        }
        String key = pipeline + "|" + idempotencyKey;
        // 先查重：相同幂等键的并发触发只会有一个真正创建运行
        String existingId = runIdByIdempotencyKey.get(key);
        if (existingId != null) {
            return runs.get(existingId);
        }
        PipelineRun run = new PipelineRun(UUID.randomUUID().toString(), pipeline, version, idempotencyKey, failAtStage);
        String winner = runIdByIdempotencyKey.putIfAbsent(key, run.getId());
        if (winner != null) {
            // 并发下另一个线程抢先，丢弃本次创建，返回已存在的运行
            return runs.get(winner);
        }
        runs.put(run.getId(), run);
        executor.submit(() -> execute(run, definition));
        return run;
    }

    private void execute(PipelineRun run, PipelineDefinition definition) {
        if (!run.transitionTo(RunStatus.RUNNING)) {
            return; // 排队期间已被取消
        }
        for (String stage : definition.stages()) {
            if (run.getStatus() != RunStatus.RUNNING) {
                return; // 已取消：不再产生任何新步骤
            }
            run.addStep(stage, "STARTED");
            sleep();
            if (run.getStatus() != RunStatus.RUNNING) {
                return;
            }
            if (stage.equals(run.getFailAtStage())) {
                run.addStep(stage, "FAILED");
                run.fail("simulated failure at stage '%s'".formatted(stage));
                return;
            }
            run.addStep(stage, "OK");
        }
        // 若此时已被取消，transitionTo 会失败，终态保持 CANCELLED，不会冒出 SUCCEEDED
        run.transitionTo(RunStatus.SUCCEEDED);
    }

    public PipelineRun cancel(String runId) {
        PipelineRun run = requireRun(runId);
        RunStatus status = run.getStatus();
        if (status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED) {
            throw new RejectionException("RUN_ALREADY_FINISHED",
                    "run %s is already in terminal state %s".formatted(runId, status));
        }
        if (!run.transitionTo(RunStatus.CANCELLED)) {
            throw new RejectionException("RUN_ALREADY_FINISHED",
                    "run %s finished concurrently and can no longer be cancelled".formatted(runId));
        }
        return run;
    }

    public PipelineRun retry(String runId) {
        PipelineRun origin = requireRun(runId);
        if (origin.getStatus() != RunStatus.FAILED) {
            throw new RejectionException("RUN_NOT_RETRYABLE",
                    "run %s is %s; only FAILED runs can be retried".formatted(runId, origin.getStatus()));
        }
        // 幂等：同一 origin 的重试只创建一次，重复请求返回同一条新运行
        String existing = retryRunIdByOriginRunId.get(runId);
        if (existing != null) {
            return runs.get(existing);
        }
        PipelineRun retry = new PipelineRun(UUID.randomUUID().toString(),
                origin.getPipelineName(), origin.getVersion(),
                origin.getIdempotencyKey() + "#retry-" + runId, origin.getFailAtStage());
        String winner = retryRunIdByOriginRunId.putIfAbsent(runId, retry.getId());
        if (winner != null) {
            return runs.get(winner);
        }
        runs.put(retry.getId(), retry);
        PipelineDefinition definition = registry.requireVersion(origin.getPipelineName(), origin.getVersion());
        executor.submit(() -> execute(retry, definition));
        return retry;
    }

    public PipelineRun getRun(String runId) {
        return runs.get(runId);
    }

    public PipelineRun requireRun(String runId) {
        PipelineRun run = runs.get(runId);
        if (run == null) {
            throw new RejectionException("RUN_NOT_FOUND", "no run with id %s".formatted(runId));
        }
        return run;
    }

    public List<PipelineRun> listRuns(String pipeline) {
        return runs.values().stream()
                .filter(r -> r.getPipelineName().equals(pipeline))
                .sorted(Comparator.comparing(PipelineRun::getCreatedAt))
                .toList();
    }

    private static void sleep() {
        try {
            Thread.sleep(STEP_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
