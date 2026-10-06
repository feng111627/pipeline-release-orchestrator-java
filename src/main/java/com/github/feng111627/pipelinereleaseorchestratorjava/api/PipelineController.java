package com.github.feng111627.pipelinereleaseorchestratorjava.api;

import com.github.feng111627.pipelinereleaseorchestratorjava.domain.EnvironmentState;
import com.github.feng111627.pipelinereleaseorchestratorjava.domain.PipelineDefinition;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.EnvironmentService;
import com.github.feng111627.pipelinereleaseorchestratorjava.service.PipelineRegistryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 流水线版本与环境提升/回滚接口。
 */
@RestController
@RequestMapping("/api/pipelines")
public class PipelineController {

    private final PipelineRegistryService registry;
    private final EnvironmentService environments;

    public PipelineController(PipelineRegistryService registry, EnvironmentService environments) {
        this.registry = registry;
        this.environments = environments;
    }

    public record SubmitVersionRequest(int version, List<String> stages) {
    }

    public record PromoteRequest(int version, String environment) {
    }

    @PostMapping("/{name}/versions")
    public PipelineDefinition submitVersion(@PathVariable String name, @RequestBody SubmitVersionRequest request) {
        return registry.submitVersion(name, request.version(), request.stages());
    }

    @GetMapping("/{name}/versions")
    public List<PipelineDefinition> listVersions(@PathVariable String name) {
        return registry.listVersions(name);
    }

    @PostMapping("/{name}/promote")
    public EnvironmentState promote(@PathVariable String name, @RequestBody PromoteRequest request) {
        return environments.promote(name, request.version(), request.environment());
    }

    @PostMapping("/{name}/environments/{env}/rollback")
    public EnvironmentState rollback(@PathVariable String name, @PathVariable String env) {
        return environments.rollback(name, env);
    }

    @GetMapping("/{name}/environments")
    public List<EnvironmentState> listEnvironments(@PathVariable String name) {
        return environments.listEnvironments(name);
    }
}
