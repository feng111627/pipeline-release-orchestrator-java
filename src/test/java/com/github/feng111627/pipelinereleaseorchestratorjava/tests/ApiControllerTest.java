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
}
