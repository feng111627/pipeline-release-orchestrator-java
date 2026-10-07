package com.github.feng111627.pipelinereleaseorchestratorjava.web;

import com.github.feng111627.pipelinereleaseorchestratorjava.dto.CreatePipelineRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.DeliverRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.PromoteRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.RollbackRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.SubmitVersionRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.TriggerRunRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.OrchestrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对外 REST 接口。
 *
 * <ul>
 *   <li>流水线：GET/POST /api/pipelines</li>
 *   <li>版本：GET/POST /api/pipelines/{id}/versions，POST .../versions/{v}/block</li>
 *   <li>环境：GET .../environments，POST .../promote、.../rollback</li>
 *   <li>运行：POST .../runs、GET .../runs、GET /api/runs/{runId}、
 *       POST /api/runs/{runId}/cancel|retry|resume</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final OrchestrationService service;

    public ApiController(OrchestrationService service) {
        this.service = service;
    }

    // ---------------- 流水线 ----------------

    @GetMapping("/pipelines")
    public List<?> listPipelines() {
        return service.listPipelines();
    }

    @PostMapping("/pipelines")
    @ResponseStatus(HttpStatus.CREATED)
    public Object createPipeline(@RequestBody CreatePipelineRequest request) {
        return service.createPipeline(request);
    }

    @GetMapping("/pipelines/{pipelineId}")
    public Object getPipeline(@PathVariable String pipelineId) {
        return service.getPipeline(pipelineId);
    }

    // ---------------- 版本 ----------------

    @GetMapping("/pipelines/{pipelineId}/versions")
    public List<?> listVersions(@PathVariable String pipelineId) {
        return service.listVersions(pipelineId);
    }

    @PostMapping("/pipelines/{pipelineId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Object submitVersion(@PathVariable String pipelineId,
                                @RequestBody SubmitVersionRequest request) {
        return service.submitVersion(pipelineId, request);
    }

    @PostMapping("/pipelines/{pipelineId}/versions/{version}/block")
    public Object blockVersion(@PathVariable String pipelineId, @PathVariable String version) {
        return service.blockVersion(pipelineId, version);
    }

    // ---------------- 环境 ----------------

    @GetMapping("/pipelines/{pipelineId}/environments")
    public List<?> listEnvironments(@PathVariable String pipelineId) {
        return service.listSlots(pipelineId);
    }

    @GetMapping("/pipelines/{pipelineId}/environments/{environment}")
    public Object getEnvironment(@PathVariable String pipelineId,
                                 @PathVariable String environment) {
        return service.getSlot(pipelineId, environment);
    }

    @PostMapping("/pipelines/{pipelineId}/promote")
    public ResponseEntity<Object> promote(@PathVariable String pipelineId,
                                          @RequestBody(required = false) PromoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.promote(pipelineId, request));
    }

    @PostMapping("/pipelines/{pipelineId}/rollback")
    public Object rollback(@PathVariable String pipelineId,
                           @RequestBody RollbackRequest request) {
        return service.rollback(pipelineId, request);
    }

    // ---------------- 批量交付 ----------------

    @PostMapping("/pipelines/{pipelineId}/deliveries")
    public ResponseEntity<Object> deliver(@PathVariable String pipelineId,
                                          @RequestBody(required = false) DeliverRequest request) {
        if (request != null && request.isDryRun()) {
            // 预演不产生任何新结论，返回 200 + 计划/失败点。
            return ResponseEntity.ok(service.previewDeliver(pipelineId, request));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.deliver(pipelineId, request));
    }

    @GetMapping("/pipelines/{pipelineId}/deliveries")
    public List<?> listDeliveries(@PathVariable String pipelineId) {
        return service.listDeliveries(pipelineId);
    }

    @GetMapping("/deliveries/{deliveryId}")
    public Object getDelivery(@PathVariable String deliveryId) {
        return service.getDelivery(deliveryId);
    }

    // ---------------- 运行 ----------------

    @PostMapping("/pipelines/{pipelineId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public Object triggerRun(@PathVariable String pipelineId,
                             @RequestBody TriggerRunRequest request) {
        return service.triggerRun(pipelineId, request);
    }

    @GetMapping("/pipelines/{pipelineId}/runs")
    public List<?> listRuns(@PathVariable String pipelineId) {
        return service.listRuns(pipelineId);
    }

    @GetMapping("/runs/{runId}")
    public Object getRun(@PathVariable String runId) {
        return service.getRun(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Object cancelRun(@PathVariable String runId) {
        return service.cancelRun(runId);
    }

    @PostMapping("/runs/{runId}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    public Object retryRun(@PathVariable String runId) {
        return service.retryRun(runId);
    }

    @PostMapping("/runs/{runId}/resume")
    public Object resumeRun(@PathVariable String runId) {
        return service.resumeRun(runId);
    }

    @GetMapping("/environments")
    public Map<String, List<String>> environmentOrder() {
        return Map.of("order", OrchestrationService.ENVIRONMENTS);
    }
}
