package com.github.feng111627.pipelinereleaseorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对外 HTTP 接口场景测试：确认所有状态与拒绝原因码都能通过接口查到。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ApiControllerTest.class);

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private MvcResult postJson(String url, String body) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            req.content(body);
        }
        return mvc.perform(req).andReturn();
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static void assertReason(MvcResult result, int httpStatus, String reason) throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals(httpStatus, result.getResponse().getStatus());
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(body.path("rejected").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals(reason, body.path("reason").asText());
    }

    private JsonNode awaitRunTerminal(String runId) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode r = node(mvc.perform(get("/api/runs/" + runId)).andReturn());
            String s = r.path("status").asText();
            if (!"PENDING".equals(s) && !"RUNNING".equals(s)) {
                return r;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("运行 " + runId + " 超时未结束");
    }

    @Test
    @DisplayName("接口场景A：版本冲突通过 HTTP 返回可区分的原因码")
    void versionConflictOverHttp() throws Exception {
        log.info("[接口A] 创建流水线 http-pipe 并提交 2.0.0");
        postJson("/api/pipelines", """
                {"id":"http-pipe","name":"HTTP流水线"}""").getResponse();
        MvcResult v1 = postJson("/api/pipelines/http-pipe/versions",
                "{\"version\":\"2.0.0\",\"commitRef\":\"abc\"}");
        org.junit.jupiter.api.Assertions.assertEquals(201, v1.getResponse().getStatus());

        MvcResult dup = postJson("/api/pipelines/http-pipe/versions",
                "{\"version\":\"2.0.0\"}");
        assertReason(dup, 409, "VERSION_ALREADY_EXISTS");
        log.info("[接口A] 重复版本 HTTP 409 reason=VERSION_ALREADY_EXISTS");

        MvcResult old = postJson("/api/pipelines/http-pipe/versions",
                "{\"version\":\"1.9.0\"}");
        assertReason(old, 409, "VERSION_REGRESSION");
        log.info("[接口A] 旧版本 HTTP 409 reason=VERSION_REGRESSION => 通过");

        MvcResult missing = mvc.perform(get("/api/pipelines/no-such-pipe/versions")).andReturn();
        assertReason(missing, 404, "PIPELINE_NOT_FOUND");
        log.info("[接口A] 不存在的流水线 HTTP 404 reason=PIPELINE_NOT_FOUND");
    }

    @Test
    @DisplayName("接口场景B：跨环境跳级提升被拒，拒绝原因能在环境接口上查到")
    void promotionRejectionVisibleOnEnvironment() throws Exception {
        log.info("[接口B] 准备流水线 http-env 与版本 1.0.0");
        postJson("/api/pipelines", "{\"id\":\"http-env\"}");
        postJson("/api/pipelines/http-env/versions", "{\"version\":\"1.0.0\"}");

        MvcResult jumped = postJson("/api/pipelines/http-env/promote",
                "{\"targetEnvironment\":\"STAGING\"}");
        assertReason(jumped, 409, "SOURCE_ENVIRONMENT_EMPTY");
        log.info("[接口B] 跳级提升 STAGING HTTP 409 reason=SOURCE_ENVIRONMENT_EMPTY");

        MvcResult slotResult = mvc.perform(get("/api/pipelines/http-env/environments/STAGING"))
                .andExpect(status().isOk()).andReturn();
        JsonNode slot = node(slotResult);
        org.junit.jupiter.api.Assertions.assertEquals("SOURCE_ENVIRONMENT_EMPTY",
                slot.path("lastRejection").asText());
        org.junit.jupiter.api.Assertions.assertTrue(slot.path("currentVersion").isNull(),
                "环境必须保持为空，无中间态");
        log.info("[接口B] 环境接口 lastRejection=SOURCE_ENVIRONMENT_EMPTY，currentVersion=null => 通过");
    }

    @Test
    @DisplayName("接口场景C：触发失败运行可查到失败步骤与原因，重试后成功")
    void failedRunVisibleThenRetrySucceeds() throws Exception {
        log.info("[接口C] 触发注入第 1 步失败的运行");
        postJson("/api/pipelines", "{\"id\":\"http-run\"}");
        postJson("/api/pipelines/http-run/versions", "{\"version\":\"1.0.0\"}");

        MvcResult triggered = postJson("/api/pipelines/http-run/runs",
                "{\"version\":\"1.0.0\",\"failAtStep\":1}");
        String runId = node(triggered).path("id").asText();
        JsonNode failed = awaitRunTerminal(runId);
        org.junit.jupiter.api.Assertions.assertEquals("FAILED", failed.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, failed.path("failedStep").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("FAILED",
                failed.path("steps").get(1).path("status").asText());
        log.info("[接口C] 运行 {} FAILED，failedStep=1，reason={}", runId,
                failed.path("failureReason").asText());

        MvcResult retried = postJson("/api/runs/" + runId + "/retry", null);
        String retryId = node(retried).path("id").asText();
        JsonNode ok = awaitRunTerminal(retryId);
        org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED", ok.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(runId, ok.path("retryOfId").asText());
        log.info("[接口C] 重试 {} 成功，retryOfId={} => 通过", retryId, runId);
    }

    @Test
    @DisplayName("接口场景D：并发触发 HTTP 409，取消后运行保持 CANCELLED 不冒新结果")
    void concurrentTriggerAndCancelOverHttp() throws Exception {
        log.info("[接口D] 触发暂停运行制造并发窗口");
        postJson("/api/pipelines", """
                {"id":"http-cancel"}""");
        postJson("/api/pipelines/http-cancel/versions", "{\"version\":\"1.0.0\"}");

        MvcResult first = postJson("/api/pipelines/http-cancel/runs",
                "{\"version\":\"1.0.0\",\"pauseBeforeStep\":0}");
        String runId = node(first).path("id").asText();
        for (int i = 0; i < 200; i++) {
            if ("RUNNING".equals(node(mvc.perform(get("/api/runs/" + runId)).andReturn())
                    .path("status").asText())) {
                break;
            }
            Thread.sleep(20);
        }

        MvcResult concurrent = postJson("/api/pipelines/http-cancel/runs",
                "{\"version\":\"1.0.0\"}");
        assertReason(concurrent, 409, "RUN_ALREADY_ACTIVE");
        log.info("[接口D] 并发触发 HTTP 409 reason=RUN_ALREADY_ACTIVE");

        mvc.perform(post("/api/runs/" + runId + "/cancel")).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/resume")).andExpect(status().isOk());
        Thread.sleep(300);
        JsonNode after = node(mvc.perform(get("/api/runs/" + runId)).andReturn());
        org.junit.jupiter.api.Assertions.assertEquals("CANCELLED", after.path("status").asText());
        log.info("[接口D] 取消并放行后状态仍为 CANCELLED => 通过");
    }

    @Test
    @DisplayName("接口场景E：批量交付一路到 PROD；预演不改状态；被拒返回失败环境与原因")
    void batchDeliveryOverHttp() throws Exception {
        log.info("[接口E] 准备流水线 http-deliver 与版本 1.0.0、2.0.0（后者隔离）");
        postJson("/api/pipelines", "{\"id\":\"http-deliver\"}");
        postJson("/api/pipelines/http-deliver/versions", "{\"version\":\"1.0.0\"}");
        postJson("/api/pipelines/http-deliver/versions", "{\"version\":\"2.0.0\"}");
        MvcResult blockedVersion = postJson("/api/pipelines/http-deliver/versions/2.0.0/block", "");
        org.junit.jupiter.api.Assertions.assertEquals(200, blockedVersion.getResponse().getStatus());

        // 预演 1.0.0 -> PROD：200、feasible=true、4 步、dryRun=true。
        MvcResult preview = postJson("/api/pipelines/http-deliver/deliveries",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"PROD\",\"dryRun\":true}");
        org.junit.jupiter.api.Assertions.assertEquals(200, preview.getResponse().getStatus());
        JsonNode plan = node(preview);
        org.junit.jupiter.api.Assertions.assertTrue(plan.path("dryRun").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(plan.path("feasible").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals(4, plan.path("steps").size());
        org.junit.jupiter.api.Assertions.assertTrue(plan.path("steps").get(0).path("fromVersion").isNull());
        // 预演不改状态：环境仍全空。
        JsonNode devSlot = node(mvc.perform(get("/api/pipelines/http-deliver/environments/DEV")).andReturn());
        org.junit.jupiter.api.Assertions.assertTrue(devSlot.path("currentVersion").isNull());
        log.info("[接口E] 预演 200 feasible=true 4 步，预演后 DEV 仍为空");

        // 不可行预演：BLOCKED 版本 -> feasible=false，失败点 DEV/VERSION_BLOCKED，仍 200。
        MvcResult badPreview = postJson("/api/pipelines/http-deliver/deliveries",
                "{\"version\":\"2.0.0\",\"targetEnvironment\":\"PROD\",\"dryRun\":true}");
        org.junit.jupiter.api.Assertions.assertEquals(200, badPreview.getResponse().getStatus());
        JsonNode badPlan = node(badPreview);
        org.junit.jupiter.api.Assertions.assertFalse(badPlan.path("feasible").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("VERSION_BLOCKED", badPlan.path("reason").asText());
        org.junit.jupiter.api.Assertions.assertEquals("DEV", badPlan.path("failedEnvironment").asText());
        org.junit.jupiter.api.Assertions.assertEquals(0, badPlan.path("failedStep").asInt());
        log.info("[接口E] 不可行预演 feasible=false，failedEnvironment=DEV reason=VERSION_BLOCKED");

        // 真实提交 BLOCKED 版本：409，响应带 environment=DEV。
        MvcResult blocked = postJson("/api/pipelines/http-deliver/deliveries",
                "{\"version\":\"2.0.0\",\"targetEnvironment\":\"PROD\"}");
        assertReason(blocked, 409, "VERSION_BLOCKED");
        org.junit.jupiter.api.Assertions.assertEquals("DEV",
                node(blocked).path("environment").asText(), "拒绝响应应指出卡在哪个环境");
        log.info("[接口E] BLOCKED 版本交付 HTTP 409 reason=VERSION_BLOCKED environment=DEV");

        // 成功交付 1.0.0 -> PROD：201，四环境到位，每环境 1 条历史。
        MvcResult ok = postJson("/api/pipelines/http-deliver/deliveries",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"PROD\"}");
        org.junit.jupiter.api.Assertions.assertEquals(201, ok.getResponse().getStatus());
        JsonNode delivery = node(ok);
        String deliveryId = delivery.path("id").asText();
        org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED", delivery.path("status").asText());
        for (String env : new String[]{"DEV", "TEST", "STAGING", "PROD"}) {
            JsonNode slot = node(mvc.perform(
                    get("/api/pipelines/http-deliver/environments/" + env)).andReturn());
            org.junit.jupiter.api.Assertions.assertEquals("1.0.0", slot.path("currentVersion").asText());
            org.junit.jupiter.api.Assertions.assertEquals(1, slot.path("history").size(),
                    env + " 应只有 1 条部署历史");
        }
        log.info("[接口E] 交付 {} 成功 201，四环境均为 1.0.0 且各 1 条历史", deliveryId);

        // 重复提交（含并发）收敛到同一条记录：都返回同一 id、201 但不新增历史。
        MvcResult dup1 = postJson("/api/pipelines/http-deliver/deliveries",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"PROD\"}");
        MvcResult dup2 = postJson("/api/pipelines/http-deliver/deliveries",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"PROD\"}");
        org.junit.jupiter.api.Assertions.assertEquals(deliveryId, node(dup1).path("id").asText());
        org.junit.jupiter.api.Assertions.assertEquals(deliveryId, node(dup2).path("id").asText());

        // 已生效交付可经列表与单查接口查到；单查不存在 -> 404 DELIVERY_NOT_FOUND。
        JsonNode listed = node(mvc.perform(get("/api/pipelines/http-deliver/deliveries")).andReturn());
        org.junit.jupiter.api.Assertions.assertEquals(1, listed.size());
        JsonNode fetched = node(mvc.perform(get("/api/deliveries/" + deliveryId)).andReturn());
        org.junit.jupiter.api.Assertions.assertEquals(deliveryId, fetched.path("id").asText());
        assertReason(mvc.perform(get("/api/deliveries/no-such")).andReturn(),
                404, "DELIVERY_NOT_FOUND");

        JsonNode prodSlot = node(mvc.perform(get("/api/pipelines/http-deliver/environments/PROD")).andReturn());
        org.junit.jupiter.api.Assertions.assertEquals(1, prodSlot.path("history").size(),
                "重复提交不得新增部署历史");
        log.info("[接口E] 重复提交收敛到 {}，交付可经列表/单查接口查到，历史未增加；未知交付 404 => 通过",
                deliveryId);
    }

    @Test
    @DisplayName("接口场景F：并发同时提交同一批量交付，只有一次真正生效")
    void concurrentBatchDeliveryOverHttp() throws Exception {
        log.info("[接口F] 准备流水线 http-deliver-concurrent 与版本 1.0.0，并发提交 8 次");
        postJson("/api/pipelines", "{\"id\":\"http-deliver-concurrent\"}");
        postJson("/api/pipelines/http-deliver-concurrent/versions", "{\"version\":\"1.0.0\"}");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.concurrent.ConcurrentLinkedQueue<String> ids = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.ConcurrentLinkedQueue<Integer> statuses = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    MvcResult r = postJson("/api/pipelines/http-deliver-concurrent/deliveries",
                            "{\"version\":\"1.0.0\",\"targetEnvironment\":\"PROD\"}");
                    statuses.add(r.getResponse().getStatus());
                    ids.add(node(r).path("id").asText());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        org.junit.jupiter.api.Assertions.assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();

        org.junit.jupiter.api.Assertions.assertEquals(threads, ids.size());
        org.junit.jupiter.api.Assertions.assertEquals(1, ids.stream().distinct().count(),
                "并发交付必须收敛到同一条记录");
        org.junit.jupiter.api.Assertions.assertTrue(statuses.stream().allMatch(s -> s == 201),
                "并发提交都应成功拿到同一结论");
        for (String env : new String[]{"DEV", "TEST", "STAGING", "PROD"}) {
            JsonNode slot = node(mvc.perform(
                    get("/api/pipelines/http-deliver-concurrent/environments/" + env)).andReturn());
            org.junit.jupiter.api.Assertions.assertEquals(1, slot.path("history").size(),
                    env + " 在并发交付后只能有 1 条部署历史");
        }
        log.info("[接口F] {} 个并发提交全部返回同一交付 {}，四环境历史各 1 条 => 通过",
                threads, ids.peek());
    }
}
