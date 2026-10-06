package com.github.feng111627.pipelinereleaseorchestratorjava.tests.service;

import com.github.feng111627.pipelinereleaseorchestratorjava.domain.EnvironmentState;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.RejectionException;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.EnvironmentService;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.PipelineRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景：跨环境按序提升与回滚 —— 跳级、环境不允许、版本不可用都必须拒绝且不留中间态。
 */
class PromotionRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(PromotionRollbackTest.class);

    private PipelineRegistryService registry;
    private EnvironmentService environments;

    @BeforeEach
    void setUp() {
        registry = new PipelineRegistryService();
        environments = new EnvironmentService(registry);
        registry.submitVersion("deploy", 1, List.of("dev", "staging", "prod"));
        registry.submitVersion("deploy", 2, List.of("dev", "staging", "prod"));
    }

    @Test
    @DisplayName("提升必须按环境顺序：跳过 dev 直接到 staging 被拒绝(STAGE_SKIPPED)且环境不留中间态")
    void skipStageRejected() {
        log.info("[场景] 跳级提升 dev -> staging -> 期望 STAGE_SKIPPED");
        RejectionException ex = assertThrows(RejectionException.class,
                () -> environments.promote("deploy", 1, "staging"));
        assertEquals("STAGE_SKIPPED", ex.getReasonCode());

        // 环境没有任何中间态：staging 仍无当前版本、无历史
        EnvironmentState staging = environments.listEnvironments("deploy").stream()
                .filter(e -> e.getEnvironment().equals("staging")).findFirst().orElse(null);
        assertTrue(staging == null || staging.getCurrentVersion() == null && staging.getHistory().isEmpty());
        log.info("[通过] 跳级被拒绝，staging 无任何部署痕迹");
    }

    @Test
    @DisplayName("按 dev -> staging -> prod 顺序提升成功，历史逐步记录")
    void sequentialPromotion() {
        log.info("[场景] 顺序提升 v1: dev -> staging -> prod");
        environments.promote("deploy", 1, "dev");
        environments.promote("deploy", 1, "staging");
        environments.promote("deploy", 1, "prod");

        for (String env : List.of("dev", "staging", "prod")) {
            EnvironmentState state = environments.listEnvironments("deploy").stream()
                    .filter(e -> e.getEnvironment().equals(env)).findFirst().orElseThrow();
            assertEquals(1, state.getCurrentVersion());
        }
        log.info("[通过] 三个环境都停在 v1");
    }

    @Test
    @DisplayName("前置环境版本不一致时不能提升：dev 在 v2 时不能把 v1 推到 staging")
    void previousStageVersionMismatch() {
        log.info("[场景] dev 已升到 v2，再把 v1 推到 staging -> 期望 STAGE_SKIPPED");
        environments.promote("deploy", 1, "dev");
        environments.promote("deploy", 2, "dev");

        RejectionException ex = assertThrows(RejectionException.class,
                () -> environments.promote("deploy", 1, "staging"));
        assertEquals("STAGE_SKIPPED", ex.getReasonCode());
        log.info("[通过] 前置环境版本不一致被拦截");
    }

    @Test
    @DisplayName("同一环境内版本不能倒退(ENV_VERSION_NOT_FORWARD)")
    void envVersionMustMoveForward() {
        log.info("[场景] dev 已在 v2，再提升 v1 -> 期望 ENV_VERSION_NOT_FORWARD");
        environments.promote("deploy", 2, "dev");
        RejectionException ex = assertThrows(RejectionException.class,
                () -> environments.promote("deploy", 1, "dev"));
        assertEquals("ENV_VERSION_NOT_FORWARD", ex.getReasonCode());
        assertEquals(2, currentOf("dev"));
        log.info("[通过] 环境内版本倒退被拒绝，当前版本仍是 v2");
    }

    @Test
    @DisplayName("提升未注册的版本被拒绝(VERSION_NOT_FOUND)，目标环境不存在被拒绝(UNKNOWN_ENVIRONMENT)")
    void unknownVersionAndEnvironment() {
        log.info("[场景] 提升不存在的版本/环境 -> VERSION_NOT_FOUND / UNKNOWN_ENVIRONMENT");
        RejectionException ex1 = assertThrows(RejectionException.class,
                () -> environments.promote("deploy", 99, "dev"));
        assertEquals("VERSION_NOT_FOUND", ex1.getReasonCode());

        RejectionException ex2 = assertThrows(RejectionException.class,
                () -> environments.promote("deploy", 1, "moon"));
        assertEquals("UNKNOWN_ENVIRONMENT", ex2.getReasonCode());
        log.info("[通过] 不可用版本与未知环境都被明确拒绝");
    }

    @Test
    @DisplayName("回滚到最近一次可用版本，再次回滚可来回切换，历史完整记录")
    void rollbackToPreviousVersion() {
        log.info("[场景] dev 上 v1 -> v2 后回滚 -> 期望回到 v1");
        environments.promote("deploy", 1, "dev");
        environments.promote("deploy", 2, "dev");

        EnvironmentState state = environments.rollback("deploy", "dev");
        assertEquals(1, state.getCurrentVersion());
        assertEquals("ROLLBACK", state.getHistory().getLast().action());

        // 再回滚一次：最近一次不同于当前的可用版本是 v2
        state = environments.rollback("deploy", "dev");
        assertEquals(2, state.getCurrentVersion());

        // 历史完整：PROMOTE 1, PROMOTE 2, ROLLBACK 1, ROLLBACK 2
        assertEquals(List.of(1, 2, 1, 2),
                state.getHistory().stream().map(EnvironmentState.DeploymentEntry::version).toList());
        log.info("[通过] 回滚到最近可用版本，部署历史完整可查");
    }

    @Test
    @DisplayName("没有可回滚目标时拒绝(NO_ROLLBACK_TARGET)，从未部署的环境拒绝(UNKNOWN_ENVIRONMENT)")
    void rollbackWithoutTarget() {
        log.info("[场景] 只部署过一个版本就回滚 -> 期望 NO_ROLLBACK_TARGET");
        environments.promote("deploy", 1, "dev");
        RejectionException ex = assertThrows(RejectionException.class,
                () -> environments.rollback("deploy", "dev"));
        assertEquals("NO_ROLLBACK_TARGET", ex.getReasonCode());
        assertEquals(1, currentOf("dev"));

        RejectionException ex2 = assertThrows(RejectionException.class,
                () -> environments.rollback("deploy", "prod"));
        assertEquals("UNKNOWN_ENVIRONMENT", ex2.getReasonCode());
        log.info("[通过] 无回滚目标与未部署环境都被拒绝，状态不变");
    }

    private int currentOf(String env) {
        return environments.listEnvironments("deploy").stream()
                .filter(e -> e.getEnvironment().equals(env)).findFirst().orElseThrow()
                .getCurrentVersion();
    }
}
