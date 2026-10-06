package com.github.feng111627.pipelinereleaseorchestratorjava.dto;

import java.util.List;

public class CreatePipelineRequest {
    private String id;
    private String name;
    private String description;
    /** 可空；为空时使用默认步骤序列。 */
    private List<String> stages;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getStages() { return stages; }
    public void setStages(List<String> stages) { this.stages = stages; }
}
