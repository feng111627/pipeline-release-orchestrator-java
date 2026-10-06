package com.github.feng111627.pipelinereleaseorchestratorjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 场景：通过对外接口验证状态与拒绝原因可查。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ApiIntegrationTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("接口层：版本提交/重复拒绝/跳级拒绝/回滚原因码都能通过 HTTP 查到")
    void rejectionReasonsExposedOverHttp() throws Exception {
        log.info("[场景] 接口层端到端：提交版本 -> 重复拒绝 -> 跳级拒绝 -> 提升 -> 回滚");
        mvc.perform(post("/api/pipelines/web/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"stages\":[\"dev\",\"prod\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 重复版本 -> 409 + VERSION_ALREADY_EXISTS
        mvc.perform(post("/api/pipelines/web/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"stages\":[\"dev\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("VERSION_ALREADY_EXISTS"));

        // 版本回退 -> 409 + VERSION_REGRESSION
        mvc.perform(post("/api/pipelines/web/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"stages\":[\"dev\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("VERSION_REGRESSION"));

        // 跳级提升 -> 409 + STAGE_SKIPPED
        mvc.perform(post("/api/pipelines/web/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"environment\":\"prod\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("STAGE_SKIPPED"));

        // 正常提升 dev，再提升 prod
        mvc.perform(post("/api/pipelines/web/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"environment\":\"dev\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1));
        mvc.perform(post("/api/pipelines/web/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"environment\":\"prod\"}"))
                .andExpect(status().isOk());

        // 只部署过一个版本，回滚 -> 409 + NO_ROLLBACK_TARGET
        mvc.perform(post("/api/pipelines/web/environments/dev/rollback"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("NO_ROLLBACK_TARGET"));

        // 环境状态可查
        mvc.perform(get("/api/pipelines/web/environments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        log.info("[通过] 所有拒绝原因码与环境状态均可通过接口查询");
    }

    @Test
    @DisplayName("接口层：触发运行后可查询运行状态与步骤，取消后状态定格")
    void runLifecycleExposedOverHttp() throws Exception {
        log.info("[场景] 接口层：触发运行 -> 取消 -> 查询状态与步骤");
        mvc.perform(post("/api/pipelines/jobs/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"stages\":[\"build\",\"test\",\"deploy\"]}"))
                .andExpect(status().isOk());

        String body = mvc.perform(post("/api/pipelines/jobs/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"idempotencyKey\":\"api-run-1\",\"failAtStage\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").isString())
                .andReturn().getResponse().getContentAsString();
        String runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("id").asText();

        // 取消后立即查询：状态为 CANCELLED，且不再变化
        mvc.perform(post("/api/runs/" + runId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        Thread.sleep(300);
        mvc.perform(get("/api/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.steps").isArray());

        // 重复取消 -> 409 + RUN_ALREADY_FINISHED
        mvc.perform(post("/api/runs/" + runId + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("RUN_ALREADY_FINISHED"));
        log.info("[通过] 运行 {} 取消后状态定格，重复取消被拒绝并给出原因码", runId);
    }
}
