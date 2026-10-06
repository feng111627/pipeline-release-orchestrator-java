package com.github.feng111627.pipelinereleaseorchestratorjava.tests;

import com.github.feng111627.pipelinereleaseorchestratorjava.dto.CreatePipelineRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.PromoteRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.RollbackRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.SubmitVersionRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.TriggerRunRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.exception.RejectedException;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeploymentRecord;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.EnvironmentSlot;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.PipelineDefinition;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RejectReason;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RunRecord;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RunStatus;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.StepStatus;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.OrchestrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心业务场景测试。每个测试方法对应一个场景，
 * 日志中先打印场景说明，再打印是否通过。
 */
class OrchestrationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationServiceTest.class);

    private final OrchestrationService service = new OrchestrationService();

    private PipelineDefinition pipeline(String id) {
        CreatePipelineRequest req = new CreatePipelineRequest();
        req.setId(id);
        req.setName("流水线-" + id);
        return service.createPipeline(req);
    }

    private void submit(String pipelineId, String version) {
        SubmitVersionRequest req = new SubmitVersionRequest();
        req.setVersion(version);
        req.setCommitRef("commit-" + version);
        service.submitVersion(pipelineId, req);
    }

    private static RejectedException expectReject(Runnable action, RejectReason reason) {
        RejectedException ex = assertThrows(RejectedException.class, action::run);
        assertEquals(reason, ex.getReason(),
                "期望拒绝原因 " + reason + "，实际 " + ex.getReason() + "（" + ex.getMessage() + "）");
        return ex;
    }

    private static RunRecord awaitTerminal(OrchestrationService service, String runId)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            RunRecord r = service.getRun(runId);
            if (r.getStatus() != RunStatus.PENDING && r.getStatus() != RunStatus.RUNNING) {
                return r;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("运行 " + runId + " 未能在限定时间内结束");
    }

    private static RunRecord awaitStatus(OrchestrationService service, String runId, RunStatus status)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            RunRecord r = service.getRun(runId);
            if (r.getStatus() == status) {
                return r;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("运行 " + runId + " 未进入状态 " + status);
    }

    // ------------------------------------------------------------------
    // 场景一：版本冲突
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景1：重复版本号 -> VERSION_ALREADY_EXISTS；更旧版本 -> VERSION_REGRESSION；历史保留")
    void versionConflicts() {
        log.info("[场景1] 开始：验证版本重复提交被识别为 VERSION_ALREADY_EXISTS，"
                + "更旧版本被识别为 VERSION_REGRESSION，且历史结论不被覆盖");
        pipeline("p-version");

        submit("p-version", "1.0.0");
        log.info("[场景1] 已提交 1.0.0，接着重复提交同一版本号");

        RejectedException duplicate = expectReject(() -> submit("p-version", "1.0.0"),
                RejectReason.VERSION_ALREADY_EXISTS);
        log.info("[场景1] 重复提交被拒绝，原因码={}，说明：{}",
                duplicate.getReason(), duplicate.getMessage());

        log.info("[场景1] 接着提交更旧的 0.9.0");
        RejectedException regression = expectReject(() -> submit("p-version", "0.9.0"),
                RejectReason.VERSION_REGRESSION);
        log.info("[场景1] 旧版本被拒绝，原因码={}，说明：{}",
                regression.getReason(), regression.getMessage());

        log.info("[场景1] 提交 1.2.0 成功后再提交 1.1.0，仍应判定为版本回退");
        submit("p-version", "1.2.0");
        expectReject(() -> submit("p-version", "1.1.0"), RejectReason.VERSION_REGRESSION);

        SubmitVersionRequest bad = new SubmitVersionRequest();
        bad.setVersion("v1-xyz");
        expectReject(() -> service.submitVersion("p-version", bad),
                RejectReason.INVALID_VERSION);

        List<?> history = service.listVersions("p-version");
        assertEquals(2, history.size(), "历史只能保留成功提交的 1.0.0 与 1.2.0");
        log.info("[场景1] 版本历史为 {}，拒绝没有覆盖任何历史结论 => 通过", history.stream()
                .map(v -> ((com.github.feng111627.pipelinereleaseorchestratorjava.model.PipelineVersion) v)
                        .getVersion()).toList());
    }

    // ------------------------------------------------------------------
    // 场景二：跨环境提升
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景2：按 DEV->TEST->STAGING->PROD 顺序提升；跳级/版本不符/版本不可用/重复提升均被拒绝")
    void crossEnvironmentPromotion() {
        log.info("[场景2] 开始：验证环境必须按顺序提升，所有非法提升都被拒绝且不留中间态");
        pipeline("p-env");
        submit("p-env", "1.0.0");
        submit("p-env", "1.1.0");

        // 未指定版本与目标环境：默认取最新可用版本进 DEV。
        DeploymentRecord toDev = service.promote("p-env", new PromoteRequest());
        assertEquals("DEV", toDev.getEnvironment());
        assertEquals("1.1.0", toDev.getVersion());
        log.info("[场景2] 默认提升：最新可用版本 1.1.0 进入 DEV");

        // 跳过 TEST/STAGING 直接到 PROD：拒绝，且 PROD 槽位记录拒绝原因。
        PromoteRequest jump = new PromoteRequest();
        jump.setTargetEnvironment("PROD");
        RejectedException jumpEx = expectReject(() -> service.promote("p-env", jump),
                RejectReason.SOURCE_ENVIRONMENT_EMPTY);
        EnvironmentSlot prod = service.getSlot("p-env", "PROD");
        assertNull(prod.getCurrentVersion(), "被拒绝后 PROD 必须仍为空，不能出现中间态");
        assertEquals(RejectReason.SOURCE_ENVIRONMENT_EMPTY, prod.getLastRejection(),
                "拒绝原因必须能通过环境接口查到");
        log.info("[场景2] 跳级提升 PROD 被拒绝，原因码={}，PROD 当前版本仍为空",
                jumpEx.getReason());

        // 指定与上游不一致的版本进 TEST：拒绝。
        PromoteRequest mismatch = new PromoteRequest();
        mismatch.setTargetEnvironment("TEST");
        mismatch.setVersion("1.0.0");
        expectReject(() -> service.promote("p-env", mismatch),
                RejectReason.PROMOTION_VERSION_MISMATCH);
        log.info("[场景2] 提升与 DEV 当前版本不一致的 1.0.0 到 TEST 被拒绝（PROMOTION_VERSION_MISMATCH）");

        // 重复提升同一版本到 DEV：拒绝。
        PromoteRequest again = new PromoteRequest();
        again.setTargetEnvironment("DEV");
        again.setVersion("1.1.0");
        expectReject(() -> service.promote("p-env", again),
                RejectReason.ALREADY_AT_ENVIRONMENT);
        log.info("[场景2] 重复提升 1.1.0 到 DEV 被拒绝（ALREADY_AT_ENVIRONMENT）");

        // 顺序提升一路到 PROD。
        service.promote("p-env", request(null, "TEST"));
        service.promote("p-env", request(null, "STAGING"));
        service.promote("p-env", request(null, "PROD"));
        for (String env : List.of("DEV", "TEST", "STAGING", "PROD")) {
            assertEquals("1.1.0", service.getSlot("p-env", env).getCurrentVersion(),
                    env + " 应当为 1.1.0");
        }
        log.info("[场景2] 顺序提升 TEST/STAGING/PROD 全部成功 => 通过");
    }

    @Test
    @DisplayName("场景2b：被标记 BLOCKED 的版本不能提升")
    void blockedVersionCannotPromote() {
        log.info("[场景2b] 开始：版本隔离后任何提升都应被拒绝");
        pipeline("p-block");
        submit("p-block", "1.0.0");
        service.blockVersion("p-block", "1.0.0");
        log.info("[场景2b] 1.0.0 已被标记为 BLOCKED");

        expectReject(() -> service.promote("p-block", new PromoteRequest()),
                RejectReason.VERSION_BLOCKED);
        assertNull(service.getSlot("p-block", "DEV").getCurrentVersion());
        log.info("[场景2b] BLOCKED 版本提升 DEV 被拒绝（VERSION_BLOCKED），环境未被改动 => 通过");
    }

    // ------------------------------------------------------------------
    // 场景三：回滚
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景3：回滚到最近一次可用版本；没有可回滚目标时拒绝")
    void rollback() {
        log.info("[场景3] 开始：验证 DEV 上 1.0.0 -> 1.1.0 后可回滚到 1.0.0");
        pipeline("p-rb");
        submit("p-rb", "1.0.0");
        submit("p-rb", "1.1.0");

        service.promote("p-rb", request("1.0.0", "DEV"));
        service.promote("p-rb", request("1.1.0", "DEV"));
        assertEquals("1.1.0", service.getSlot("p-rb", "DEV").getCurrentVersion());

        RollbackRequest rb = new RollbackRequest();
        rb.setEnvironment("DEV");
        DeploymentRecord record = service.rollback("p-rb", rb);
        assertEquals("1.0.0", record.getVersion());
        assertEquals("1.1.0", record.getFromVersion());
        assertEquals("1.0.0", service.getSlot("p-rb", "DEV").getCurrentVersion());
        log.info("[场景3] 回滚成功：{} -> {}，记录动作={}", record.getFromVersion(),
                record.getVersion(), record.getAction());

        // 未部署过的环境不能回滚，拒绝原因可查询。
        RollbackRequest rbProd = new RollbackRequest();
        rbProd.setEnvironment("PROD");
        expectReject(() -> service.rollback("p-rb", rbProd), RejectReason.NO_ROLLBACK_TARGET);
        assertEquals(RejectReason.NO_ROLLBACK_TARGET,
                service.getSlot("p-rb", "PROD").getLastRejection());
        log.info("[场景3] 空环境回滚被拒绝（NO_ROLLBACK_TARGET），原因可在环境接口查到");

        // 唯一历史版本被隔离后，再无可回滚目标。
        service.blockVersion("p-rb", "1.0.0");
        service.rollback("p-rb", rb); // 当前 1.0.0 -> 回滚到仍可用的 1.1.0
        expectReject(() -> service.rollback("p-rb", rb), RejectReason.NO_ROLLBACK_TARGET);
        log.info("[场景3] 旧版本被隔离后再次回滚被拒绝（NO_ROLLBACK_TARGET）=> 通过");
    }

    // ------------------------------------------------------------------
    // 场景四：并发触发 / 取消 / 重试
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景4：并发触发不串状态；进行中重复触发被拒；取消后不会再冒出成功结果")
    void concurrentTriggerAndCancel() throws InterruptedException {
        log.info("[场景4] 开始：触发一个在第 0 步前暂停的运行，制造进行中窗口");
        pipeline("p-run");
        submit("p-run", "1.0.0");

        TriggerRunRequest pauseReq = new TriggerRunRequest();
        pauseReq.setVersion("1.0.0");
        pauseReq.setPauseBeforeStep(0);
        RunRecord first = service.triggerRun("p-run", pauseReq);
        awaitStatus(service, first.getId(), RunStatus.RUNNING);
        log.info("[场景4] 运行 {} 已进入 RUNNING 并停在暂停点", first.getId());

        // 进行中再次触发：必须拒绝，且不能产生第二条运行。
        TriggerRunRequest concurrent = new TriggerRunRequest();
        concurrent.setVersion("1.0.0");
        int runsBefore = service.listRuns("p-run").size();
        expectReject(() -> service.triggerRun("p-run", concurrent),
                RejectReason.RUN_ALREADY_ACTIVE);
        assertEquals(runsBefore, service.listRuns("p-run").size(),
                "被拒绝的并发触发不能创建运行记录");
        log.info("[场景4] 进行中并发触发被拒绝（RUN_ALREADY_ACTIVE），运行记录数未增加");

        // 取消：立即进入 CANCELLED 终态。
        RunRecord cancelled = service.cancelRun(first.getId());
        assertEquals(RunStatus.CANCELLED, cancelled.getStatus());
        log.info("[场景4] 取消运行 {}，状态={}", first.getId(), cancelled.getStatus());

        // 即使放行并等待，也不能再冒出新的运行结果。
        service.resumeRun(first.getId());
        Thread.sleep(300);
        RunRecord afterWait = service.getRun(first.getId());
        assertEquals(RunStatus.CANCELLED, afterWait.getStatus(),
                "取消后执行线程不得再写入成功结果");
        assertFalse(afterWait.getSteps().stream()
                .anyMatch(s -> s.getStatus() == StepStatus.SUCCEEDED));
        log.info("[场景4] 放行并等待 300ms 后状态仍为 CANCELLED，没有步骤成功");

        // 取消后流水线可再次触发，新运行不受影响。
        TriggerRunRequest next = new TriggerRunRequest();
        next.setVersion("1.0.0");
        RunRecord second = service.triggerRun("p-run", next);
        RunRecord secondFinal = awaitTerminal(service, second.getId());
        assertEquals(RunStatus.SUCCEEDED, secondFinal.getStatus());
        log.info("[场景4] 取消后新触发的运行 {} 正常 SUCCEEDED => 通过", second.getId());
    }

    @Test
    @DisplayName("场景4b：失败步骤与原因可见；失败可重试；成功运行拒绝重试；进行中重复重试被拒")
    void failureAndRetry() throws InterruptedException {
        log.info("[场景4b] 开始：触发注入第 2 步失败的运行");
        pipeline("p-retry");
        submit("p-retry", "1.0.0");

        TriggerRunRequest failReq = new TriggerRunRequest();
        failReq.setVersion("1.0.0");
        failReq.setFailAtStep(1);
        RunRecord failed = awaitTerminal(service, service.triggerRun("p-retry", failReq).getId());
        assertEquals(RunStatus.FAILED, failed.getStatus());
        assertEquals(1, failed.getFailedStep());
        assertEquals(StepStatus.FAILED, failed.getSteps().get(1).getStatus());
        assertEquals(StepStatus.CANCELLED, failed.getSteps().get(2).getStatus());
        assertNotNull(failed.getFailureReason());
        log.info("[场景4b] 运行 FAILED，停在步骤 {}，原因：{}；后续步骤标记为 CANCELLED",
                failed.getFailedStep(), failed.getFailureReason());

        RunRecord retried = awaitTerminal(service, service.retryRun(failed.getId()).getId());
        assertEquals(RunStatus.SUCCEEDED, retried.getStatus());
        assertEquals(failed.getId(), retried.getRetryOfId(), "重试记录必须能追溯原运行");
        log.info("[场景4b] 重试运行 {}（来源 {}）成功", retried.getId(), failed.getId());

        // 成功的运行不允许重试。
        expectReject(() -> service.retryRun(retried.getId()), RejectReason.RETRY_NOT_ALLOWED);
        log.info("[场景4b] 对成功运行重试被拒绝（RETRY_NOT_ALLOWED）=> 通过");
    }

    @Test
    @DisplayName("场景4c：进行中的运行被重复重试 -> RETRY_ALREADY_ACTIVE")
    void retryWhileActiveRejected() throws InterruptedException {
        log.info("[场景4c] 开始：对暂停中的运行发起重试，应被明确拒绝");
        pipeline("p-retry2");
        submit("p-retry2", "1.0.0");

        TriggerRunRequest pauseReq = new TriggerRunRequest();
        pauseReq.setVersion("1.0.0");
        pauseReq.setPauseBeforeStep(0);
        RunRecord paused = service.triggerRun("p-retry2", pauseReq);
        awaitStatus(service, paused.getId(), RunStatus.RUNNING);

        expectReject(() -> service.retryRun(paused.getId()), RejectReason.RETRY_ALREADY_ACTIVE);
        log.info("[场景4c] 对进行中运行重试被拒绝（RETRY_ALREADY_ACTIVE）=> 通过");

        service.cancelRun(paused.getId());
    }

    private static PromoteRequest request(String version, String target) {
        PromoteRequest pr = new PromoteRequest();
        pr.setVersion(version);
        pr.setTargetEnvironment(target);
        return pr;
    }
}
