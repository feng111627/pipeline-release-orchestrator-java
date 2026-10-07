package com.github.feng111627.pipelinereleaseorchestratorjava.tests;

import com.github.feng111627.pipelinereleaseorchestratorjava.dto.BatchDeliveryRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.CreatePipelineRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.SubmitVersionRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.exception.DeliveryPlanRejectedException;
import com.github.feng111627.pipelinereleaseorchestratorjava.exception.RejectedException;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeliveryPreview;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeliveryRecord;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeliveryStep;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeliveryStepType;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.EnvironmentSlot;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.PipelineDefinition;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RejectReason;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.OrchestrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批量交付核心场景测试。日志带 [批量N] 前缀，说明每个场景验证什么、是否通过。
 */
class BatchDeliveryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(BatchDeliveryServiceTest.class);

    private final OrchestrationService service = new OrchestrationService();

    private static final List<String> ENVS = List.of("DEV", "TEST", "STAGING", "PROD");

    private void pipeline(String id) {
        CreatePipelineRequest req = new CreatePipelineRequest();
        req.setId(id);
        req.setName("批量-" + id);
        service.createPipeline(req);
    }

    private void submit(String pipelineId, String version) {
        SubmitVersionRequest req = new SubmitVersionRequest();
        req.setVersion(version);
        service.submitVersion(pipelineId, req);
    }

    private static BatchDeliveryRequest delivery(String version, String target) {
        BatchDeliveryRequest req = new BatchDeliveryRequest();
        req.setVersion(version);
        req.setTargetEnvironment(target);
        return req;
    }

    /** 抓取四个环境的当前版本与每个环境的历史记录数，用于断言「状态完全没变」。 */
    private List<String> snapshot(String pipelineId) {
        List<String> snap = new ArrayList<>();
        for (String env : ENVS) {
            EnvironmentSlot slot = service.getSlot(pipelineId, env);
            snap.add(env + ":" + slot.getCurrentVersion() + "#" + slot.getHistory().size());
        }
        return snap;
    }

    private static DeliveryPlanRejectedException expectPlanReject(Runnable action,
                                                                  RejectReason reason) {
        DeliveryPlanRejectedException ex = assertThrows(DeliveryPlanRejectedException.class,
                action::run);
        assertEquals(reason, ex.getReason());
        return ex;
    }

    // ------------------------------------------------------------------
    // 批量场景1：一路提升到目标，全部生效；步骤顺序与 from->to 正确
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量1：一个版本从空盘一口气交付到 PROD，四个环境全部生效，步骤顺序正确")
    void deliverAllWayToProd() {
        log.info("[批量1] 开始：空盘交付 1.0.0 到 PROD，应依次提升 DEV->TEST->STAGING->PROD");
        pipeline("b1");
        submit("b1", "1.0.0");

        DeliveryRecord record = service.deliver("b1", delivery("1.0.0", "PROD"));
        assertFalse(record.isReused());
        assertEquals(4, record.getSteps().size());
        for (int i = 0; i < 4; i++) {
            DeliveryStep step = record.getSteps().get(i);
            assertEquals(ENVS.get(i), step.environment());
            assertEquals(DeliveryStepType.PROMOTE, step.action());
            assertNull(step.fromVersion());
            assertEquals("1.0.0", step.toVersion());
        }
        assertEquals(4, record.getDeploymentIds().size(), "四个环境各产生一条部署记录");
        for (String env : ENVS) {
            assertEquals("1.0.0", service.getSlot("b1", env).getCurrentVersion(),
                    env + " 应当生效为 1.0.0");
            assertEquals(1, service.getSlot("b1", env).getHistory().size(),
                    env + " 应只有一条部署历史");
        }
        log.info("[批量1] 交付 {} 完成，四个环境均为 1.0.0，部署记录={} => 通过",
                record.getId(), record.getDeploymentIds());
    }

    @Test
    @DisplayName("批量1b：部分环境已是目标版本时跳过，只变更其余环境")
    void deliverWithSkippedEnvironments() {
        log.info("[批量1b] 开始：DEV 已是 2.0.0，交付 2.0.0 到 STAGING，DEV 应 SKIP");
        pipeline("b1b");
        submit("b1b", "2.0.0");
        service.deliver("b1b", delivery("2.0.0", "DEV")); // 先让 DEV 有 2.0.0

        DeliveryRecord record = service.deliver("b1b", delivery("2.0.0", "STAGING"));
        assertEquals(3, record.getSteps().size());
        assertEquals(DeliveryStepType.SKIP, record.getSteps().get(0).action(),
                "DEV 已是目标版本，应标记为 SKIP");
        assertEquals("2.0.0", record.getSteps().get(0).fromVersion());
        assertEquals(DeliveryStepType.PROMOTE, record.getSteps().get(1).action());
        assertEquals(DeliveryStepType.PROMOTE, record.getSteps().get(2).action());
        assertEquals(2, record.getDeploymentIds().size(), "只有 TEST/STAGING 产生新部署记录");
        assertEquals(1, service.getSlot("b1b", "DEV").getHistory().size(),
                "被 SKIP 的 DEV 不应新增历史");
        assertEquals("2.0.0", service.getSlot("b1b", "TEST").getCurrentVersion());
        assertEquals("2.0.0", service.getSlot("b1b", "STAGING").getCurrentVersion());
        log.info("[批量1b] DEV 被跳过、TEST/STAGING 生效，部署记录={} => 通过",
                record.getDeploymentIds());
    }

    // ------------------------------------------------------------------
    // 批量场景2：被整体拒绝 -> 所有环境状态与部署历史完全不变
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量2：目标环境已是该版本（且无同键已生效交付）-> 整体拒绝，状态与历史完全不变")
    void rejectWhenAlreadyAtTarget() {
        log.info("[批量2] 开始：通过单环境提升让 DEV/TEST 为 1.0.0，再批量交付 1.0.0 到 TEST 应整体拒绝");
        pipeline("b2");
        submit("b2", "1.0.0");
        // 用既有的单环境提升把状态推到 DEV=TEST=1.0.0，但不产生任何「批量交付」记录。
        com.github.feng111627.pipelinereleaseorchestratorjava.dto.PromoteRequest pr =
                new com.github.feng111627.pipelinereleaseorchestratorjava.dto.PromoteRequest();
        pr.setVersion("1.0.0");
        pr.setTargetEnvironment("DEV");
        service.promote("b2", pr);
        pr.setTargetEnvironment("TEST");
        service.promote("b2", pr);

        List<String> before = snapshot("b2");
        DeliveryPlanRejectedException ex = expectPlanReject(
                () -> service.deliver("b2", delivery("1.0.0", "TEST")),
                RejectReason.ALREADY_AT_ENVIRONMENT);
        assertEquals("TEST", ex.getFailureEnvironment(), "必须指出卡在 TEST");
        assertEquals(before, snapshot("b2"), "被拒绝后所有环境状态与历史必须完全不变");
        assertTrue(service.listDeliveries("b2").isEmpty(), "被拒绝的交付不产生交付记录");
        log.info("[批量2] 拒绝原因={}，卡在环境={}，状态/历史快照一致 => 通过",
                ex.getReason(), ex.getFailureEnvironment());
    }

    @Test
    @DisplayName("批量2b：版本被 BLOCKED -> 整体拒绝，所有环境（含前面的）完全没变")
    void rejectWhenVersionBlockedLeavesNoTrace() {
        log.info("[批量2b] 开始：空盘交付被隔离的 1.0.0 到 PROD，任何环境都不得被改动");
        pipeline("b2b");
        submit("b2b", "1.0.0");
        service.blockVersion("b2b", "1.0.0");

        List<String> before = snapshot("b2b");
        DeliveryPlanRejectedException ex = expectPlanReject(
                () -> service.deliver("b2b", delivery("1.0.0", "PROD")),
                RejectReason.VERSION_BLOCKED);
        assertEquals("DEV", ex.getFailureEnvironment(), "第一个需要变更的环境 DEV 被卡");
        assertEquals(before, snapshot("b2b"), "被拒绝后四个环境与历史必须完全不变");
        assertTrue(service.listDeliveries("b2b").isEmpty());
        log.info("[批量2b] 拒绝原因={}，卡在 {}，四个环境仍为空、无部署记录 => 通过",
                ex.getReason(), ex.getFailureEnvironment());
    }

    @Test
    @DisplayName("批量2c：版本不存在 -> 404 原因码的整体拒绝，不留任何痕迹")
    void rejectWhenVersionMissing() {
        log.info("[批量2c] 开始：交付不存在的 9.9.9 到 TEST");
        pipeline("b2c");
        submit("b2c", "1.0.0");
        List<String> before = snapshot("b2c");

        DeliveryPlanRejectedException ex = expectPlanReject(
                () -> service.deliver("b2c", delivery("9.9.9", "TEST")),
                RejectReason.VERSION_NOT_FOUND);
        assertEquals("DEV", ex.getFailureEnvironment());
        assertEquals(before, snapshot("b2c"));
        log.info("[批量2c] 拒绝原因={}，卡在 {}，状态不变 => 通过",
                ex.getReason(), ex.getFailureEnvironment());
    }

    // ------------------------------------------------------------------
    // 批量场景3：预演不改状态；可通给出 from->to；不可通给出被卡位置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量3：预演给出顺序与 from->to 且不改任何状态")
    void previewDoesNotMutate() {
        log.info("[批量3] 开始：先让 DEV=1.0.0，再预演交付 1.0.0 到 STAGING");
        pipeline("b3");
        submit("b3", "1.0.0");
        service.deliver("b3", delivery("1.0.0", "DEV"));
        List<String> before = snapshot("b3");

        DeliveryPreview preview = service.previewDelivery("b3", delivery("1.0.0", "STAGING"));
        assertTrue(preview.isDryRun());
        assertTrue(preview.isDeliverable());
        assertEquals(3, preview.getSteps().size());
        assertEquals(DeliveryStepType.SKIP, preview.getSteps().get(0).action());
        assertEquals(DeliveryStepType.PROMOTE, preview.getSteps().get(1).action());
        assertNull(preview.getSteps().get(1).fromVersion(), "TEST 当前为空");
        assertEquals("1.0.0", preview.getSteps().get(1).toVersion());
        assertEquals(before, snapshot("b3"), "预演绝不能改环境状态");
        assertTrue(service.listDeliveries("b3").size() == 1, "预演不产生交付记录");
        log.info("[批量3] 预演环节={}，预演后状态快照与预演前一致 => 通过",
                preview.getSteps().stream().map(s -> s.environment() + ":" + s.action()).toList());
    }

    @Test
    @DisplayName("批量3b：注定失败的预演给出被卡环境与原因，且不改状态")
    void previewRejection() {
        log.info("[批量3b] 开始：隔离版本后预演，应返回 deliverable=false 且指出卡点");
        pipeline("b3b");
        submit("b3b", "1.0.0");
        service.blockVersion("b3b", "1.0.0");
        List<String> before = snapshot("b3b");

        DeliveryPreview preview = service.previewDelivery("b3b", delivery("1.0.0", "PROD"));
        assertFalse(preview.isDeliverable());
        assertEquals("VERSION_BLOCKED", preview.getReason());
        assertEquals("DEV", preview.getFailureEnvironment());
        assertNotNull(preview.getMessage());
        assertEquals(before, snapshot("b3b"), "失败的预演也不能改状态");
        log.info("[批量3b] 预演 deliverable=false，reason={}，failureEnvironment={} => 通过",
                preview.getReason(), preview.getFailureEnvironment());
    }

    // ------------------------------------------------------------------
    // 批量场景4：重复提交收敛；并发提交只有一次真正生效
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量4：同键重复提交收敛到同一次结果（reused），不再产生部署记录")
    void repeatedDeliveryConverges() {
        log.info("[批量4] 开始：连续两次提交同一 (流水线,版本,目标)");
        pipeline("b4");
        submit("b4", "1.0.0");

        DeliveryRecord first = service.deliver("b4", delivery("1.0.0", "PROD"));
        assertFalse(first.isReused());
        DeliveryRecord second = service.deliver("b4", delivery("1.0.0", "PROD"));
        assertTrue(second.isReused(), "重复提交必须标记为复用");
        assertEquals(first.getId(), second.getId(), "必须收敛到同一条交付记录");
        assertEquals(first.getCreatedAt(), second.getCreatedAt());
        assertEquals(1, service.listDeliveries("b4").size(), "只应有一条交付记录");
        for (String env : ENVS) {
            assertEquals(1, service.getSlot("b4", env).getHistory().size(),
                    env + " 不得因重复提交新增历史");
        }
        // 已生效交付可通过现有/新增接口查到。
        assertEquals(first.getId(), service.getDelivery(first.getId()).getId());
        assertEquals(4, service.listDeliveries("b4").get(0).getDeploymentIds().size());
        log.info("[批量4] 两次提交收敛到交付 {}，第二次 reused=true => 通过", first.getId());
    }

    @Test
    @DisplayName("批量4b：并发提交同一交付只有一次真正生效，其余复用同一结果")
    void concurrentDeliveryAppliesOnce() throws Exception {
        log.info("[批量4b] 开始：8 个线程同时交付 1.0.0 到 PROD");
        pipeline("b4b");
        submit("b4b", "1.0.0");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<DeliveryRecord>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.deliver("b4b", delivery("1.0.0", "PROD")));
        }
        List<Future<DeliveryRecord>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        String uniqueId = null;
        int applied = 0;
        int reused = 0;
        for (Future<DeliveryRecord> f : futures) {
            DeliveryRecord r = f.get();
            if (uniqueId == null) {
                uniqueId = r.getId();
            }
            assertEquals(uniqueId, r.getId(), "所有并发结果必须收敛到同一条交付记录");
            if (r.isReused()) {
                reused++;
            } else {
                applied++;
            }
        }
        assertEquals(1, applied, "只能有一次真正生效");
        assertEquals(threads - 1, reused, "其余必须为复用");
        assertEquals(1, service.listDeliveries("b4b").size());
        for (String env : ENVS) {
            assertEquals(1, service.getSlot("b4b", env).getHistory().size(),
                    env + " 只能有一条部署历史");
        }
        log.info("[批量4b] 8 个并发结果同为 {}，生效 1 次、复用 {} 次 => 通过",
                uniqueId, reused);
    }

    // ------------------------------------------------------------------
    // 批量场景5：被拒后可以修正状态重新交付（拒绝不产生幂等记录）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量5：被整体拒绝后解除原因，可重新交付成功（拒绝不产生幂等记录）")
    void canDeliverAgainAfterRejection() {
        log.info("[批量5] 开始：先用单环境提升让 DEV=TEST=1.0.0，批量交付 1.0.0 到 TEST 被拒，"
                + "再把版本推进到 2.0.0 后交付成功");
        pipeline("b5");
        submit("b5", "1.0.0");
        submit("b5", "2.0.0");
        com.github.feng111627.pipelinereleaseorchestratorjava.dto.PromoteRequest pr =
                new com.github.feng111627.pipelinereleaseorchestratorjava.dto.PromoteRequest();
        pr.setVersion("1.0.0");
        pr.setTargetEnvironment("DEV");
        service.promote("b5", pr);
        pr.setTargetEnvironment("TEST");
        service.promote("b5", pr);

        // 交付 1.0.0 到 TEST：目标已是且无同键交付 -> 拒绝，且不占用幂等键。
        expectPlanReject(() -> service.deliver("b5", delivery("1.0.0", "TEST")),
                RejectReason.ALREADY_AT_ENVIRONMENT);

        // 交付 2.0.0 到 TEST：DEV 当前 1.0.0，会先把 DEV 提升到 2.0.0 再让 TEST=2.0.0。
        DeliveryRecord record = service.deliver("b5", delivery("2.0.0", "TEST"));
        assertFalse(record.isReused());
        assertEquals("2.0.0", service.getSlot("b5", "DEV").getCurrentVersion());
        assertEquals("2.0.0", service.getSlot("b5", "TEST").getCurrentVersion());
        assertEquals(2, service.getSlot("b5", "DEV").getHistory().size());
        assertEquals(2, service.getSlot("b5", "TEST").getHistory().size());
        log.info("[批量5] 拒绝后修正状态再交付 2.0.0 成功 => 通过");
    }

    @Test
    @DisplayName("批量6：参数缺失/未知环境按普通请求错误拒绝")
    void invalidRequests() {
        log.info("[批量6] 开始：验证缺字段与未知环境的拒绝");
        pipeline("b6");
        submit("b6", "1.0.0");
        assertThrows(RejectedException.class,
                () -> service.deliver("b6", delivery("1.0.0", null)));
        // 未知环境不进入交付规划，走统一的 INVALID_ENVIRONMENT。
        RejectedException badEnv = assertThrows(RejectedException.class,
                () -> service.deliver("b6", delivery("1.0.0", "SPACE")));
        assertEquals(RejectReason.INVALID_ENVIRONMENT, badEnv.getReason());
        log.info("[批量6] 缺字段/未知环境被拒绝，原因={} => 通过", badEnv.getReason());
    }

    @Test
    @DisplayName("批量7：交付记录不存在 -> DELIVERY_NOT_FOUND")
    void deliveryNotFound() {
        log.info("[批量7] 开始：查询不存在的交付记录");
        pipeline("b7");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> service.getDelivery("delivery-404"));
        assertEquals(RejectReason.DELIVERY_NOT_FOUND, ex.getReason());
        log.info("[批量7] 查询不存在交付被拒绝，原因={} => 通过", ex.getReason());
    }
}
