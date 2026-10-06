package com.github.feng111627.pipelinereleaseorchestratorjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 流水线定义。一个流水线拥有按提交顺序排列的版本历史。
 * 历史只追加、不覆盖；版本号必须严格递增。
 */
public class PipelineDefinition {
    private final String id;
    private final String name;
    private final String description;
    /** 该流水线运行时要依次执行的步骤名。 */
    private final List<String> stages;
    /** 版本历史，按提交时间追加。 */
    private final List<PipelineVersion> versions = new ArrayList<>();

    public PipelineDefinition(String id, String name, String description, List<String> stages) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.stages = new ArrayList<>(stages);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<String> getStages() { return List.copyOf(stages); }
    public List<PipelineVersion> getVersions() { return versions; }
}
