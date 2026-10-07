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

    // ------------------------------------------------------------------
    // 接口场景 E/F/G：批量交付
    // ------------------------------------------------------------------

    @Test
    @DisplayName("接口场景E：批量交付预演不改状态，正式交付一路到 PROD")
    void batchDeliveryPreviewAndApply() throws Exception {
        log.info("[接口E] 准备流水线 http-batch 与版本 1.0.0，先预演再正式交付");
        postJson("/api/pipelines", "{\"id\":\"http-batch\"}");
        postJson("/api/pipelines/http-batch/versions", "{\"version\":\"1.0.0\"}");

        MvcResult preview = postJson("/api/pipelines/http-batch/deliveries/preview",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"PROD\"}");
        org.junit.jupiter.api.Assertions.assertEquals(200, preview.getResponse().getStatus());
        JsonNode pv = node(preview);
        org.junit.jupiter.api.Assertions.assertTrue(pv.path("dryRun").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(pv.path("deliverable").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals(4, pv.path("steps").size());
        org.junit.jupiter.api.Assertions.assertEquals("DEV",
                pv.path("steps").get(0).path("environment").asText());
        // 预演不得改状态：交付列表为空、PROD 仍为空。
        org.junit.jupiter.api.Assertions.assertEquals("[]",
                mvc.perform(get("/api/pipelines/http-batch/deliveries")).andReturn()
                        .getResponse().getContentAsString());
        JsonNode prodSlot = node(mvc.perform(get("/api/pipelines/http-batch/environments/PROD"))
                .andReturn());
        org.junit.jupiter.api.Assertions.assertTrue(prodSlot.path("currentVersion").isNull());
        log.info("[接口E] 预演给出 4 个环节，且未产生交付记录、PROD 仍为空");

        MvcResult applied = postJson("/api/pipelines/http-batch/deliveries",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"PROD\"}");
        org.junit.jupiter.api.Assertions.assertEquals(201, applied.getResponse().getStatus());
        JsonNode record = node(applied);
        String deliveryId = record.path("id").asText();
        org.junit.jupiter.api.Assertions.assertEquals(4, record.path("deploymentIds").size());
        org.junit.jupiter.api.Assertions.assertFalse(record.path("reused").asBoolean());
        for (String env : new String[] {"DEV", "TEST", "STAGING", "PROD"}) {
            JsonNode slot = node(mvc.perform(
                    get("/api/pipelines/http-batch/environments/" + env)).andReturn());
            org.junit.jupiter.api.Assertions.assertEquals("1.0.0",
                    slot.path("currentVersion").asText(), env + " 应为 1.0.0");
        }
        // 交付记录可通过接口查到，环境部署历史也带得到 deliveryId。
        JsonNode fetched = node(mvc.perform(get("/api/deliveries/" + deliveryId)).andReturn());
        org.junit.jupiter.api.Assertions.assertEquals(deliveryId, fetched.path("id").asText());
        log.info("[接口E] 正式交付 {} 生效，四环境均为 1.0.0，交付记录可查询 => 通过", deliveryId);
    }

    @Test
    @DisplayName("接口场景F：批量交付整体被拒 HTTP 409，响应含被卡环境，状态不变")
    void batchDeliveryRejectionOverHttp() throws Exception {
        log.info("[接口F] 隔离版本后批量交付，应 409 且指出 failureEnvironment");
        postJson("/api/pipelines", "{\"id\":\"http-batch-rej\"}");
        postJson("/api/pipelines/http-batch-rej/versions", "{\"version\":\"1.0.0\"}");
        mvc.perform(post("/api/pipelines/http-batch-rej/versions/1.0.0/block"))
                .andExpect(status().isOk());

        MvcResult rejected = postJson("/api/pipelines/http-batch-rej/deliveries",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"PROD\"}");
        assertReason(rejected, 409, "VERSION_BLOCKED");
        JsonNode body = node(rejected);
        org.junit.jupiter.api.Assertions.assertEquals("DEV",
                body.path("failureEnvironment").asText(), "必须指出被卡的环境");
        // 无任何交付记录，环境全空。
        org.junit.jupiter.api.Assertions.assertEquals("[]",
                mvc.perform(get("/api/pipelines/http-batch-rej/deliveries")).andReturn()
                        .getResponse().getContentAsString());
        JsonNode dev = node(mvc.perform(get("/api/pipelines/http-batch-rej/environments/DEV"))
                .andReturn());
        org.junit.jupiter.api.Assertions.assertTrue(dev.path("currentVersion").isNull());

        // 预演同一场景返回 200 但 deliverable=false，同样不改状态。
        MvcResult preview = postJson("/api/pipelines/http-batch-rej/deliveries/preview",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"PROD\"}");
        org.junit.jupiter.api.Assertions.assertEquals(200, preview.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertFalse(node(preview).path("deliverable").asBoolean());
        log.info("[接口F] 正式交付 409 reason=VERSION_BLOCKED failureEnvironment=DEV；"
                + "预演 200 deliverable=false，状态均不变 => 通过");
    }

    @Test
    @DisplayName("接口场景G：重复提交批量交付 201->200 收敛到同一条记录（reused=true）")
    void repeatedBatchDeliveryOverHttp() throws Exception {
        log.info("[接口G] 连续两次 HTTP 提交同一批量交付");
        postJson("/api/pipelines", "{\"id\":\"http-batch-idem\"}");
        postJson("/api/pipelines/http-batch-idem/versions", "{\"version\":\"1.0.0\"}");

        MvcResult first = postJson("/api/pipelines/http-batch-idem/deliveries",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"STAGING\"}");
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());
        String id = node(first).path("id").asText();

        MvcResult second = postJson("/api/pipelines/http-batch-idem/deliveries",
                "{\"version\":\"1.0.0\",\"targetEnvironment\":\"STAGING\"}");
        org.junit.jupiter.api.Assertions.assertEquals(200, second.getResponse().getStatus(),
                "重复提交应返回 200 复用而非 201");
        JsonNode body = node(second);
        org.junit.jupiter.api.Assertions.assertEquals(id, body.path("id").asText());
        org.junit.jupiter.api.Assertions.assertTrue(body.path("reused").asBoolean());

        JsonNode list = node(mvc.perform(get("/api/pipelines/http-batch-idem/deliveries"))
                .andReturn());
        org.junit.jupiter.api.Assertions.assertEquals(1, list.size(), "只能有一条交付记录");
        for (String env : new String[] {"DEV", "TEST", "STAGING"}) {
            JsonNode slot = node(mvc.perform(
                    get("/api/pipelines/http-batch-idem/environments/" + env)).andReturn());
            org.junit.jupiter.api.Assertions.assertEquals(1,
                    slot.path("history").size(), env + " 只能有一条部署历史");
        }
        log.info("[接口G] 两次提交收敛到 {}，第二次 200 reused=true => 通过", id);
    }
}
