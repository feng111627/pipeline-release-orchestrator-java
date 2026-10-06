package com.github.feng111627.pipelinereleaseorchestratorjava.tests.service;

import com.github.feng111627.pipelinereleaseorchestratorjava.domain.PipelineDefinition;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.RejectionException;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.PipelineRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景：流水线版本化 —— 重复版本与版本回退必须被拒绝，历史不可覆盖。
 */
class VersioningTest {

    private static final Logger log = LoggerFactory.getLogger(VersioningTest.class);

    private final PipelineRegistryService registry = new PipelineRegistryService();

    @Test
    @DisplayName("重复提交同一版本号被拒绝(VERSION_ALREADY_EXISTS)且历史不被覆盖")
    void duplicateVersionRejected() {
        log.info("[场景] 重复版本提交 -> 期望 VERSION_ALREADY_EXISTS");
        PipelineDefinition first = registry.submitVersion("deploy", 1, List.of("dev", "staging", "prod"));

        RejectionException ex = assertThrows(RejectionException.class,
                () -> registry.submitVersion("deploy", 1, List.of("dev")));
        assertEquals("VERSION_ALREADY_EXISTS", ex.getReasonCode());

        // 历史仍是第一次提交的定义，未被覆盖
        List<PipelineDefinition> history = registry.listVersions("deploy");
        assertEquals(1, history.size());
        assertEquals(List.of("dev", "staging", "prod"), history.get(0).stages());
        assertEquals(first.submittedAt(), history.get(0).submittedAt());
        log.info("[通过] 重复版本被拒绝，历史定义保持不变");
    }

    @Test
    @DisplayName("提交更旧的版本号被拒绝(VERSION_REGRESSION)")
    void regressionVersionRejected() {
        log.info("[场景] 版本回退 -> 期望 VERSION_REGRESSION");
        registry.submitVersion("deploy", 1, List.of("dev"));
        registry.submitVersion("deploy", 3, List.of("dev"));

        RejectionException ex = assertThrows(RejectionException.class,
                () -> registry.submitVersion("deploy", 2, List.of("dev")));
        assertEquals("VERSION_REGRESSION", ex.getReasonCode());

        // 回退的版本没有进入历史
        assertEquals(List.of(1, 3), registry.listVersions("deploy").stream()
                .map(PipelineDefinition::version).toList());
        log.info("[通过] 回退版本被拒绝，历史只保留 1 和 3");
    }

    @Test
    @DisplayName("版本历史完整保留且按版本号升序可查")
    void historyPreserved() {
        log.info("[场景] 连续提交多个版本 -> 历史完整保留");
        registry.submitVersion("deploy", 1, List.of("dev"));
        registry.submitVersion("deploy", 2, List.of("dev", "prod"));
        registry.submitVersion("deploy", 5, List.of("dev", "staging", "prod"));

        List<PipelineDefinition> history = registry.listVersions("deploy");
        assertEquals(List.of(1, 2, 5), history.stream().map(PipelineDefinition::version).toList());
        assertEquals(List.of("dev", "staging", "prod"), registry.getVersion("deploy", 5).stages());
        log.info("[通过] 三个版本均可查询，历史未被覆盖");
    }

    @Test
    @DisplayName("查询不存在的版本返回 null，requireVersion 抛 VERSION_NOT_FOUND")
    void missingVersion() {
        log.info("[场景] 查询不存在的版本 -> VERSION_NOT_FOUND");
        assertNull(registry.getVersion("ghost", 1));
        RejectionException ex = assertThrows(RejectionException.class,
                () -> registry.requireVersion("ghost", 1));
        assertEquals("VERSION_NOT_FOUND", ex.getReasonCode());
        log.info("[通过] 不存在的版本被明确识别");
    }
}
