package com.github.feng111627.pipelinereleaseorchestratorjava.tests.service;

import com.github.feng111627.pipelinereleaseorchestratorjava.domain.PipelineRun;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.RejectionException;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.RunStatus;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.PipelineRegistryService;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.RunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景：并发触发、取消与失败重试 —— 状态不串、重试幂等、取消后不再产生新结果。
 */
class RunConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(RunConcurrencyTest.class);

    private PipelineRegistryService registry;
    private RunService runs;

    @BeforeEach
    void setUp() {
        registry = new PipelineRegistryService();
        runs = new RunService(registry);
        registry.submitVersion("deploy", 1, List.of("dev", "staging", "prod"));
    }

    @Test
    @DisplayName("同一幂等键并发触发 16 次只产生一条运行，且运行正常完成")
    void concurrentTriggerSameKeyProducesSingleRun() throws Exception {
        log.info("[场景] 16 线程用同一幂等键并发触发 -> 期望只有 1 条运行");
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Set<String> runIds = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            IntStream.range(0, threads).forEach(i -> pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    runIds.add(runs.trigger("deploy", 1, "release-2026-10-06", null).getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, runIds.size(), "同一幂等键的并发触发必须收敛到同一条运行");
        assertEquals(1, runs.listRuns("deploy").size());

        PipelineRun run = runs.getRun(runIds.iterator().next());
        awaitTerminal(run);
        assertEquals(RunStatus.SUCCEEDED, run.getStatus());
        // 每个 stage 都有 STARTED + OK 两步，走到哪一步可查
        assertEquals(6, run.getSteps().size());
        log.info("[通过] 并发触发收敛为运行 {}，状态 {}", run.getId(), run.getStatus());
    }

    @Test
    @DisplayName("不同幂等键的并发触发产生各自独立的运行，状态互不串扰")
    void concurrentTriggerDifferentKeysIsolated() throws Exception {
        log.info("[场景] 8 个不同幂等键并发触发 -> 8 条独立运行");
        int threads = 8;
        Set<String> runIds = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            IntStream.range(0, threads).forEach(i -> pool.submit(() ->
                    runIds.add(runs.trigger("deploy", 1, "key-" + i, null).getId())));
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(8, runIds.size());
        for (String id : runIds) {
            PipelineRun run = runs.getRun(id);
            awaitTerminal(run);
            assertEquals(RunStatus.SUCCEEDED, run.getStatus());
            assertEquals(6, run.getSteps().size());
        }
        log.info("[通过] 8 条运行各自完成，步骤数一致，状态未串");
    }

    @Test
    @DisplayName("取消运行后状态为 CANCELLED，且之后不再冒出新的步骤或结果")
    void cancelStopsRunAndNoFurtherResults() throws Exception {
        log.info("[场景] 触发后立即取消 -> CANCELLED，且步骤数不再增长");
        PipelineRun run = runs.trigger("deploy", 1, "cancel-me", null);
        PipelineRun cancelled = runs.cancel(run.getId());
        assertEquals(RunStatus.CANCELLED, cancelled.getStatus());

        int stepsAtCancel = run.getSteps().size();
        // 等待远超完整执行所需的时间，确认没有新步骤、状态没有变成 SUCCEEDED
        Thread.sleep(RunService.STEP_DELAY_MS * 6);
        assertEquals(RunStatus.CANCELLED, run.getStatus());
        assertEquals(stepsAtCancel, run.getSteps().size(), "取消后不允许再产生新的步骤记录");

        // 终态后再次取消必须被拒绝
        RejectionException ex = assertThrows(RejectionException.class, () -> runs.cancel(run.getId()));
        assertEquals("RUN_ALREADY_FINISHED", ex.getReasonCode());
        log.info("[通过] 取消生效，步骤定格在 {} 条，重复取消被拒绝", stepsAtCancel);
    }

    @Test
    @DisplayName("失败运行记录失败步骤与原因，重试幂等：重复重试返回同一条新运行")
    void failedRunRetryIsIdempotent() {
        log.info("[场景] 在 staging 失败 -> 重试 -> 重复重试 -> 期望幂等");
        PipelineRun failed = runs.trigger("deploy", 1, "will-fail", "staging");
        awaitTerminal(failed);
        assertEquals(RunStatus.FAILED, failed.getStatus());
        assertEquals("simulated failure at stage 'staging'", failed.getFailureReason());
        // 能看出走到哪一步：staging 是最后一个有记录的步骤
        assertEquals("staging", failed.getSteps().getLast().stage());
        assertEquals("FAILED", failed.getSteps().getLast().outcome());

        PipelineRun retry1 = runs.retry(failed.getId());
        PipelineRun retry2 = runs.retry(failed.getId());
        assertEquals(retry1.getId(), retry2.getId(), "对同一条失败运行的重复重试必须幂等");
        assertNotEquals(failed.getId(), retry1.getId());

        awaitTerminal(retry1);
        assertEquals(RunStatus.FAILED, retry1.getStatus()); // 同样的 failAtStage，仍失败
        log.info("[通过] 失败原因可查，重试幂等返回运行 {}", retry1.getId());
    }

    @Test
    @DisplayName("非失败状态的运行不允许重试(RUN_NOT_RETRYABLE)")
    void retryOnlyFailedRuns() {
        log.info("[场景] 重试成功/进行中的运行 -> 期望 RUN_NOT_RETRYABLE");
        PipelineRun ok = runs.trigger("deploy", 1, "ok-run", null);
        awaitTerminal(ok);
        assertEquals(RunStatus.SUCCEEDED, ok.getStatus());
        RejectionException ex = assertThrows(RejectionException.class, () -> runs.retry(ok.getId()));
        assertEquals("RUN_NOT_RETRYABLE", ex.getReasonCode());
        log.info("[通过] 成功运行重试被拒绝");
    }

    @Test
    @DisplayName("触发未注册版本被拒绝(VERSION_NOT_FOUND)，缺少幂等键被拒绝(MISSING_IDEMPOTENCY_KEY)")
    void triggerValidation() {
        log.info("[场景] 非法触发参数 -> VERSION_NOT_FOUND / MISSING_IDEMPOTENCY_KEY");
        RejectionException ex1 = assertThrows(RejectionException.class,
                () -> runs.trigger("deploy", 42, "k", null));
        assertEquals("VERSION_NOT_FOUND", ex1.getReasonCode());
        RejectionException ex2 = assertThrows(RejectionException.class,
                () -> runs.trigger("deploy", 1, " ", null));
        assertEquals("MISSING_IDEMPOTENCY_KEY", ex2.getReasonCode());
        log.info("[通过] 非法触发被明确拒绝");
    }

    private static void awaitTerminal(PipelineRun run) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            RunStatus s = run.getStatus();
            if (s == RunStatus.SUCCEEDED || s == RunStatus.FAILED || s == RunStatus.CANCELLED) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("run %s did not reach a terminal state in time, still %s".formatted(run.getId(), run.getStatus()));
    }
}
