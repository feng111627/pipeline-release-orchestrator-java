package com.github.feng111627.pipelinereleaseorchestratorjava.tests;

import com.github.feng111627.pipelinereleaseorchestratorjava.dto.CreatePipelineRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.DeliverRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.PromoteRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.SubmitVersionRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.exception.RejectedException;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeliveryPlan;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeliveryRecord;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeliveryStep;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.EnvironmentSlot;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RejectReason;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.OrchestrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批量交付场景测试。日志带 [批量场景N] 前缀，先说明在验证什么，再打印是否通过。
 */
class BatchDeliveryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(BatchDeliveryServiceTest.class);
    private static final List<String> ENVS = Arrays.asList("DEV", "TEST", "STAGING", "PROD");

    private final OrchestrationService service = new OrchestrationService();

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

    private static DeliverRequest deliver(String version, String target) {
        DeliverRequest req = new DeliverRequest();
        req.setVersion(version);
        req.setTargetEnvironment(target);
        return req;
    }

    private static RejectedException expectReject(Runnable action, RejectReason reason) {
        RejectedException ex = assertThrows(RejectedException.class, action::run);
        assertEquals(reason, ex.getReason(),
                "期望拒绝原因 " + reason + "，实际 " + ex.getReason() + "（" + ex.getMessage() + "）");
        return ex;
    }

    /** 各环境当前版本快照（顺序 DEV..PROD）。 */
    private List<String> currentVersions(String pipelineId) {
        List<String> out = new ArrayList<>();
        for (String env : ENVS) {
            out.add(service.getSlot(pipelineId, env).getCurrentVersion());
        }
        return out;
    }

    /** 各环境部署历史条数快照。 */
    private List<Integer> historySizes(String pipelineId) {
        List<Integer> out = new ArrayList<>();
        for (String env : ENVS) {
            out.add(service.getSlot(pipelineId, env).getHistory().size());
        }
        return out;
    }

    private void assertSlots(String pipelineId, List<String> wantVersions, List<Integer> wantHistory) {
        assertEquals(wantVersions, currentVersions(pipelineId), "环境当前版本快照不符");
        assertEquals(wantHistory, historySizes(pipelineId), "各环境部署历史条数不符");
    }

    // ------------------------------------------------------------------
    // 场景5：批量交付一路跑通
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量场景1：指定版本与目标环境，按序一路交付到 PROD，全部环境生效且记录可查")
    void deliverAllTheWayToProd() {
        log.info("[批量场景1] 开始：空盘流水线提交 1.0.0，批量交付到 PROD");
        pipeline("b1");
        submit("b1", "1.0.0");

        DeliveryRecord record = service.deliver("b1", deliver("1.0.0", "PROD"));
        log.info("[批量场景1] 交付完成 id={} status={} target={}", record.getId(),
                record.getStatus(), record.getTargetEnvironment());

        assertEquals(DeliveryRecord.SUCCEEDED, record.getStatus());
        assertEquals("1.0.0", record.getVersion());
        assertEquals("PROD", record.getTargetEnvironment());
        assertEquals(4, record.getSteps().size(), "应包含 DEV..PROD 四个步骤");
        for (DeliveryStep step : record.getSteps()) {
            assertNull(step.getFromVersion(), env(step) + " 交付前应为空");
            assertEquals("1.0.0", step.getToVersion());
            assertTrue(step.isChanged(), env(step) + " 应当发生变更");
            assertNotNull(step.getDeploymentId(), env(step) + " 应有关联部署记录 id");
            assertEquals(step.getStep(), ENVS.indexOf(step.getEnvironment()));
        }
        assertSlots("b1", Arrays.asList("1.0.0", "1.0.0", "1.0.0", "1.0.0"),
                Arrays.asList(1, 1, 1, 1));
        log.info("[批量场景1] 四个环境当前版本={}，每环境各 1 条 PROMOTE 历史",
                currentVersions("b1"));

        // 已生效交付能从现有接口（列表 + 单查）查到。
        List<DeliveryRecord> listed = service.listDeliveries("b1");
        assertEquals(1, listed.size());
        assertEquals(record.getId(), listed.get(0).getId());
        assertEquals(record.getId(), service.getDelivery(record.getId()).getId());
        log.info("[批量场景1] 交付记录可经 listDeliveries/getDelivery 查到 => 通过");
    }

    @Test
    @DisplayName("批量场景1b：部分到位时，已到位环境收敛为空操作，只补齐缺口")
    void deliverFillsOnlyGaps() {
        log.info("[批量场景1b] 先用单环境提升把 1.0.0 放到 DEV，再批量交付到 STAGING");
        pipeline("b1b");
        submit("b1b", "1.0.0");
        PromoteRequest toDev = new PromoteRequest();
        toDev.setVersion("1.0.0");
        toDev.setTargetEnvironment("DEV");
        service.promote("b1b", toDev);

        DeliveryRecord record = service.deliver("b1b", deliver("1.0.0", "STAGING"));
        assertEquals(3, record.getSteps().size());
        assertFalse(record.getSteps().get(0).isChanged(), "DEV 已到位，应为收敛空操作");
        assertNull(record.getSteps().get(0).getDeploymentId(), "收敛步不产生部署记录");
        assertTrue(record.getSteps().get(1).isChanged());
        assertTrue(record.getSteps().get(2).isChanged());
        assertSlots("b1b", Arrays.asList("1.0.0", "1.0.0", "1.0.0", null),
                Arrays.asList(1, 1, 1, 0));
        log.info("[批量场景1b] DEV 不重复写入（仍 1 条历史），TEST/STAGING 各新增 1 条 => 通过");
    }

    // ------------------------------------------------------------------
    // 场景6：被拒时整体零改动
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量场景2：版本不可用/不存在/目标已是该版本 -> 整体拒绝，环境与历史完全不变")
    void rejectionChangesNothing() {
        log.info("[批量场景2] 开始：空盘，验证各类拒绝都不写任何环境/历史/交付记录");
        pipeline("b2");
        submit("b2", "1.0.0");
        submit("b2", "2.0.0");

        // 2.1 BLOCKED 版本：卡在第一步 DEV，VERSION_BLOCKED，全盘不变。
        service.blockVersion("b2", "2.0.0");
        RejectedException blocked = expectReject(
                () -> service.deliver("b2", deliver("2.0.0", "PROD")),
                RejectReason.VERSION_BLOCKED);
        assertEquals("DEV", blocked.getEnvironment(), "应指出卡在 DEV");
        assertSlots("b2", Arrays.asList(null, null, null, null), Arrays.asList(0, 0, 0, 0));
        assertTrue(service.listDeliveries("b2").isEmpty(), "被拒交付不留下记录");
        log.info("[批量场景2] BLOCKED 版本交付被拒，卡在 {}，原因码={}，四环境全空、历史全 0",
                blocked.getEnvironment(), blocked.getReason());

        // 2.2 版本不存在：VERSION_NOT_FOUND，全盘不变。
        expectReject(() -> service.deliver("b2", deliver("9.9.9", "PROD")),
                RejectReason.VERSION_NOT_FOUND);
        assertSlots("b2", Arrays.asList(null, null, null, null), Arrays.asList(0, 0, 0, 0));
        log.info("[批量场景2] 不存在版本交付被拒（VERSION_NOT_FOUND），状态仍全空");

        // 2.3 用「单环境提升」把 1.0.0 放到 DEV、TEST（注意：不经批量交付，因此没有
        //     对应的已生效交付记录可收敛）。此时再批量交付 1.0.0 -> TEST，应在目标环境
        //     TEST 处整体拒绝 ALREADY_AT_ENVIRONMENT，且 DEV/TEST 状态与历史完全不变。
        PromoteRequest toDev = new PromoteRequest();
        toDev.setVersion("1.0.0");
        toDev.setTargetEnvironment("DEV");
        service.promote("b2", toDev);
        PromoteRequest toTest = new PromoteRequest();
        toTest.setTargetEnvironment("TEST");
        service.promote("b2", toTest);
        assertSlots("b2", Arrays.asList("1.0.0", "1.0.0", null, null),
                Arrays.asList(1, 1, 0, 0));

        RejectedException already = expectReject(
                () -> service.deliver("b2", deliver("1.0.0", "TEST")),
                RejectReason.ALREADY_AT_ENVIRONMENT);
        assertEquals("TEST", already.getEnvironment(), "应指出卡在目标环境 TEST");
        assertSlots("b2", Arrays.asList("1.0.0", "1.0.0", null, null),
                Arrays.asList(1, 1, 0, 0));
        assertTrue(service.listDeliveries("b2").isEmpty(),
                "目标已是该版本的新交付被整体拒绝，不留下交付记录");
        log.info("[批量场景2] 目标已是该版本（无已生效记录）被拒，卡在 {}，原因码={}，DEV/TEST 状态与历史无任何变化 => 通过",
                already.getEnvironment(), already.getReason());
    }

    @Test
    @DisplayName("批量场景2b：非法请求（缺版本/缺目标/未知环境）直接拒绝且零改动")
    void invalidRequestRejected() {
        pipeline("b2b");
        submit("b2b", "1.0.0");
        expectReject(() -> service.deliver("b2b", deliver(null, "PROD")),
                RejectReason.INVALID_REQUEST);
        expectReject(() -> service.deliver("b2b", deliver("1.0.0", null)),
                RejectReason.INVALID_REQUEST);
        expectReject(() -> service.deliver("b2b", deliver("1.0.0", "MOON")),
                RejectReason.INVALID_ENVIRONMENT);
        expectReject(() -> service.deliver("missing", deliver("1.0.0", "PROD")),
                RejectReason.PIPELINE_NOT_FOUND);
        assertSlots("b2b", Arrays.asList(null, null, null, null), Arrays.asList(0, 0, 0, 0));
        log.info("[批量场景2b] 缺版本/缺目标/未知环境/未知流水线分别被拒，环境零改动 => 通过");
    }

    // ------------------------------------------------------------------
    // 场景7：预演不改状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量场景3：预演给出每环境 from->to；注定失败时给出失败点与原因；预演绝不改状态")
    void previewNeverChangesState() {
        log.info("[批量场景3] 开始：空盘预演 1.0.0 -> PROD");
        pipeline("b3");
        submit("b3", "1.0.0");

        DeliveryPlan plan = service.previewDeliver("b3", deliver("1.0.0", "PROD"));
        assertTrue(plan.isDryRun());
        assertTrue(plan.isFeasible());
        assertNull(plan.getReason());
        assertEquals(4, plan.getSteps().size());
        for (DeliveryStep step : plan.getSteps()) {
            assertNull(step.getFromVersion(), env(step) + " 预演 fromVersion 应为 null");
            assertEquals("1.0.0", step.getToVersion());
            assertTrue(step.isChanged());
        }
        // 预演后：环境全空、历史全 0、没有任何交付记录。
        assertSlots("b3", Arrays.asList(null, null, null, null), Arrays.asList(0, 0, 0, 0));
        assertTrue(service.listDeliveries("b3").isEmpty());
        log.info("[批量场景3] 可行预演给出 4 步 null->1.0.0，预演后环境仍全空、无交付记录");

        // 真实交付到 DEV，再预演到 PROD：DEV 应为收敛空操作，其余补缺口。
        service.deliver("b3", deliver("1.0.0", "DEV"));
        DeliveryPlan partial = service.previewDeliver("b3", deliver("1.0.0", "PROD"));
        assertTrue(partial.isFeasible());
        assertFalse(partial.getSteps().get(0).isChanged(), "DEV 已到位，预演应显示收敛");
        assertEquals("1.0.0", partial.getSteps().get(0).getFromVersion());
        for (int i = 1; i < 4; i++) {
            assertTrue(partial.getSteps().get(i).isChanged(), ENVS.get(i) + " 应显示将变更");
        }
        assertSlots("b3", Arrays.asList("1.0.0", null, null, null), Arrays.asList(1, 0, 0, 0));
        log.info("[批量场景3] 预演正确区分收敛步(DEV)与变更步(TEST/STAGING/PROD)，状态未被预演改动");

        // 不可行预演：隔离 2.0.0 后预演 -> PROD，feasible=false 且指出卡在 DEV/VERSION_BLOCKED。
        submit("b3", "2.0.0");
        service.blockVersion("b3", "2.0.0");
        DeliveryPlan blockedPlan = service.previewDeliver("b3", deliver("2.0.0", "PROD"));
        assertFalse(blockedPlan.isFeasible());
        assertEquals("VERSION_BLOCKED", blockedPlan.getReason());
        assertEquals("DEV", blockedPlan.getFailedEnvironment());
        assertEquals(0, blockedPlan.getFailedStep());
        assertNotNull(blockedPlan.getMessage());
        log.info("[批量场景3] 不可行预演 feasible=false，卡在 {}(step={})，原因={}，说明：{}",
                blockedPlan.getFailedEnvironment(), blockedPlan.getFailedStep(),
                blockedPlan.getReason(), blockedPlan.getMessage());

        // 目标已是该版本的预演：feasible=false，失败点在目标环境。
        DeliveryPlan alreadyPlan = service.previewDeliver("b3", deliver("1.0.0", "DEV"));
        assertFalse(alreadyPlan.isFeasible());
        assertEquals("ALREADY_AT_ENVIRONMENT", alreadyPlan.getReason());
        assertEquals("DEV", alreadyPlan.getFailedEnvironment());

        // 所有预演过后，真实状态与仅做过一次 deliver->DEV 完全一致。
        assertSlots("b3", Arrays.asList("1.0.0", null, null, null), Arrays.asList(1, 0, 0, 0));
        assertEquals(1, service.listDeliveries("b3").size());
        log.info("[批量场景3] 多次预演后真实状态不变，交付记录仍只有 1 条 => 通过");
    }

    // ------------------------------------------------------------------
    // 场景8：重复提交与并发提交只生效一次
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量场景4：重复提交同一交付收敛到同一条记录，不重复生效")
    void duplicateSubmissionConverges() {
        log.info("[批量场景4] 开始：交付 1.0.0 -> PROD 后重复提交");
        pipeline("b4");
        submit("b4", "1.0.0");
        submit("b4", "1.1.0");

        DeliveryRecord first = service.deliver("b4", deliver("1.0.0", "PROD"));
        DeliveryRecord second = service.deliver("b4", deliver("1.0.0", "PROD"));
        DeliveryRecord third = service.deliver("b4", deliver("1.0.0", "PROD"));
        assertEquals(first.getId(), second.getId(), "重复提交必须返回同一条交付");
        assertEquals(first.getId(), third.getId());
        assertSlots("b4", Arrays.asList("1.0.0", "1.0.0", "1.0.0", "1.0.0"),
                Arrays.asList(1, 1, 1, 1));
        assertEquals(1, service.listDeliveries("b4").size(), "重复提交不得新增交付记录");
        log.info("[批量场景4] 三次提交收敛到 {}，历史仍每环境 1 条，交付记录仅 1 条", first.getId());

        // 即使后续用单环境提升把 DEV 换成了别的版本，重复提交仍返回原交付、绝不重跑。
        PromoteRequest newer = new PromoteRequest();
        newer.setVersion("1.1.0");
        newer.setTargetEnvironment("DEV");
        service.promote("b4", newer);
        DeliveryRecord again = service.deliver("b4", deliver("1.0.0", "PROD"));
        assertEquals(first.getId(), again.getId(), "状态漂移后重复提交仍收敛到原记录");
        assertEquals("1.1.0", service.getSlot("b4", "DEV").getCurrentVersion(),
                "收敛返回不得重跑、不得改动环境");
        assertEquals(2, service.getSlot("b4", "DEV").getHistory().size(),
                "收敛返回不得新增部署历史");
        log.info("[批量场景4] DEV 已被改成 1.1.0 后重复提交仍返回原记录且不重跑 => 通过");
    }

    @Test
    @DisplayName("批量场景4b：并发同时提交同一交付，只有一次真正生效")
    void concurrentSubmissionAppliesOnce() throws InterruptedException {
        log.info("[批量场景4b] 开始：8 个线程同时提交同一交付 1.0.0 -> PROD");
        pipeline("b4b");
        submit("b4b", "1.0.0");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS); // 尽量同时冲锁
                    ids.add(service.deliver("b4b", deliver("1.0.0", "PROD")).getId());
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发任务应全部结束");
        pool.shutdownNow();

        assertTrue(errors.isEmpty(), "并发提交不应有异常：" + errors);
        assertEquals(threads, ids.size());
        long distinct = ids.stream().distinct().count();
        assertEquals(1, distinct, "所有并发提交必须收敛到同一条交付，实际 id=" + ids);
        assertSlots("b4b", Arrays.asList("1.0.0", "1.0.0", "1.0.0", "1.0.0"),
                Arrays.asList(1, 1, 1, 1));
        assertEquals(1, service.listDeliveries("b4b").size(),
                "并发下只能有一条交付记录");
        log.info("[批量场景4b] {} 个并发提交全部拿到同一条交付 {}，四环境各仅 1 条历史 => 通过",
                threads, ids.peek());
    }

    @Test
    @DisplayName("批量场景4c：不同目标环境是相互独立的交付，分别可查")
    void differentTargetsAreIndependent() {
        pipeline("b4c");
        submit("b4c", "1.0.0");
        DeliveryRecord toTest = service.deliver("b4c", deliver("1.0.0", "TEST"));
        DeliveryRecord toProd = service.deliver("b4c", deliver("1.0.0", "PROD"));
        assertNotEquals(toTest.getId(), toProd.getId());
        assertEquals(2, service.listDeliveries("b4c").size());
        // 第二次交付把 STAGING/PROD 补齐，DEV/TEST 作为收敛步不重复写入。
        assertSlots("b4c", Arrays.asList("1.0.0", "1.0.0", "1.0.0", "1.0.0"),
                Arrays.asList(1, 1, 1, 1));
        log.info("[批量场景4c] ->TEST 与 ->PROD 是两条独立交付，收敛步未重复写历史 => 通过");
    }

    private static String env(DeliveryStep step) {
        return step.getEnvironment();
    }
}
