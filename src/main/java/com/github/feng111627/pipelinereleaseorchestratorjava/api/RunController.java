package com.github.feng111627.pipelinereleaseorchestratorjava.api;

import com.github.feng111627.pipelinereleaseorchestratorjava.domain.PipelineRun;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.RunService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 运行触发、取消、重试与查询接口。
 */
@RestController
@RequestMapping("/api")
public class RunController {

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    public record TriggerRequest(int version, String idempotencyKey, String failAtStage) {
    }

    @PostMapping("/pipelines/{name}/runs")
    public PipelineRun trigger(@PathVariable String name, @RequestBody TriggerRequest request) {
        return runs.trigger(name, request.version(), request.idempotencyKey(), request.failAtStage());
    }

    @PostMapping("/runs/{runId}/cancel")
    public PipelineRun cancel(@PathVariable String runId) {
        return runs.cancel(runId);
    }

    @PostMapping("/runs/{runId}/retry")
    public PipelineRun retry(@PathVariable String runId) {
        return runs.retry(runId);
    }

    @GetMapping("/runs/{runId}")
    public PipelineRun getRun(@PathVariable String runId) {
        return runs.requireRun(runId);
    }

    @GetMapping("/pipelines/{name}/runs")
    public List<PipelineRun> listRuns(@PathVariable String name) {
        return runs.listRuns(name);
    }
}
