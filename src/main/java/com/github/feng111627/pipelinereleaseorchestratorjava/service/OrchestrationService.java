package com.github.feng111627.pipelinereleaseorchestratorjava.service;

import com.github.feng111627.pipelinereleaseorchestratorjava.dto.CreatePipelineRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.PromoteRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.RollbackRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.SubmitVersionRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.dto.TriggerRunRequest;
import com.github.feng111627.pipelinereleaseorchestratorjava.exception.RejectedException;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeploymentAction;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.DeploymentRecord;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.EnvironmentSlot;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.PipelineDefinition;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.PipelineVersion;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RejectReason;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RunRecord;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RunStatus;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.RunStep;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.StepStatus;
import com.github.feng111627.pipelinereleaseorchestratorjava.model.VersionStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流水线发布编排核心服务。
 *
 * <p>所有改变状态的操作都在同一个内存锁内完成校验与写入，保证「先校验、后落库」的原子性：
 * 任何拒绝都发生在任何写入之前，环境不会出现中间态。运行步骤的推进也在同一把锁内变更，
 * 使取消标记对执行线程具有确定性的约束力。</p>
 */
@Service
public class OrchestrationService {

    /** 固定的环境提升顺序，不可跳过。 */
    public static final List<String> ENVIRONMENTS = List.of("DEV", "TEST", "STAGING", "PROD");

    private static final List<String> DEFAULT_STAGES =
            List.of("CHECKOUT", "BUILD", "UNIT_TEST", "PACKAGE");

    private final Object mutex = new Object();
    private final Map<String, PipelineDefinition> pipelines = new ConcurrentHashMap<>();
    private final Map<String, List<EnvironmentSlot>> slotsByPipeline = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runsById = new ConcurrentHashMap<>();
    private final Map<String, List<RunRecord>> runsByPipeline = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> pauseGates = new ConcurrentHashMap<>();
    private final AtomicLong deploymentSeq = new AtomicLong();
    private final AtomicLong runSeq = new AtomicLong();

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "pipeline-run-worker");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------------
    // 流水线
    // ---------------------------------------------------------------------

    public List<PipelineDefinition> listPipelines() {
        return new ArrayList<>(pipelines.values());
    }

    public PipelineDefinition createPipeline(CreatePipelineRequest request) {
        if (request == null || isBlank(request.getId())) {
            throw reject(RejectReason.INVALID_REQUEST, "流水线 id 不能为空");
        }
        String id = request.getId().trim();
        List<String> stages = request.getStages();
        if (stages == null || stages.isEmpty()) {
            stages = DEFAULT_STAGES;
        }
        for (String s : stages) {
            if (isBlank(s)) {
                throw reject(RejectReason.INVALID_REQUEST, "步骤名不能为空");
            }
        }
        synchronized (mutex) {
            if (pipelines.containsKey(id)) {
                throw reject(RejectReason.INVALID_REQUEST, "流水线已存在: " + id);
            }
            PipelineDefinition def = new PipelineDefinition(
                    id,
                    isBlank(request.getName()) ? id : request.getName().trim(),
                    request.getDescription(),
                    stages);
            pipelines.put(id, def);
            slotsByPipeline.put(id, createSlots(id));
            runsByPipeline.put(id, new ArrayList<>());
            return def;
        }
    }

    public PipelineDefinition getPipeline(String pipelineId) {
        PipelineDefinition def = pipelines.get(pipelineId);
        if (def == null) {
            throw reject(RejectReason.PIPELINE_NOT_FOUND, "流水线不存在: " + pipelineId);
        }
        return def;
    }

    // ---------------------------------------------------------------------
    // 版本化
    // ---------------------------------------------------------------------

    public List<PipelineVersion> listVersions(String pipelineId) {
        PipelineDefinition def = getPipeline(pipelineId);
        synchronized (mutex) {
            return List.copyOf(def.getVersions());
        }
    }

    public PipelineVersion submitVersion(String pipelineId, SubmitVersionRequest request) {
        PipelineDefinition def = getPipeline(pipelineId);
        if (request == null || isBlank(request.getVersion())) {
            throw reject(RejectReason.INVALID_REQUEST, "版本号不能为空");
        }
        String version = request.getVersion().trim();
        int[] parsed = parseVersion(version);

        synchronized (mutex) {
            List<PipelineVersion> versions = def.getVersions();
            for (PipelineVersion v : versions) {
                if (v.getVersion().equals(version)) {
                    // 历史结论不可覆盖：明确区分「版本已存在」。
                    throw reject(RejectReason.VERSION_ALREADY_EXISTS,
                            "版本 " + version + " 已存在，不能重复提交");
                }
            }
            if (!versions.isEmpty()) {
                PipelineVersion latest = versions.get(versions.size() - 1);
                if (compareVersion(parsed, parseVersion(latest.getVersion())) <= 0) {
                    // 比最新版本更旧或相等（相等前面已拦截）：版本回退。
                    throw reject(RejectReason.VERSION_REGRESSION,
                            "版本 " + version + " 不高于当前最新版本 " + latest.getVersion()
                                    + "，禁止版本回退");
                }
            }
            PipelineVersion created = new PipelineVersion(
                    pipelineId, version,
                    request.getCommitRef() == null ? "" : request.getCommitRef().trim(),
                    Instant.now(), VersionStatus.AVAILABLE);
            versions.add(created);
            return created;
        }
    }

    public PipelineVersion blockVersion(String pipelineId, String version) {
        PipelineDefinition def = getPipeline(pipelineId);
        if (isBlank(version)) {
            throw reject(RejectReason.INVALID_REQUEST, "版本号不能为空");
        }
        synchronized (mutex) {
            PipelineVersion found = findVersion(def, version.trim());
            found.setStatus(VersionStatus.BLOCKED);
            return found;
        }
    }

    // ---------------------------------------------------------------------
    // 环境：提升 / 回滚
    // ---------------------------------------------------------------------

    public List<EnvironmentSlot> listSlots(String pipelineId) {
        getPipeline(pipelineId);
        synchronized (mutex) {
            return List.copyOf(slotsByPipeline.get(pipelineId));
        }
    }

    public EnvironmentSlot getSlot(String pipelineId, String environment) {
        getPipeline(pipelineId);
        int idx = envIndex(environment);
        synchronized (mutex) {
            return slotsByPipeline.get(pipelineId).get(idx);
        }
    }

    public DeploymentRecord promote(String pipelineId, PromoteRequest request) {
        PipelineDefinition def = getPipeline(pipelineId);
        if (request == null) {
            throw reject(RejectReason.INVALID_REQUEST, "请求体不能为空");
        }
        String requestedVersion = isBlank(request.getVersion()) ? null : request.getVersion().trim();
        String targetEnv = isBlank(request.getTargetEnvironment()) ? null
                : request.getTargetEnvironment().trim().toUpperCase();

        synchronized (mutex) {
            List<EnvironmentSlot> slots = slotsByPipeline.get(pipelineId);

            // 1) 确定目标环境。
            int targetIdx;
            if (targetEnv != null) {
                targetIdx = envIndex(targetEnv);
            } else {
                int highest = -1;
                for (int i = 0; i < slots.size(); i++) {
                    if (slots.get(i).getCurrentVersion() != null) {
                        highest = i;
                    }
                }
                if (highest == ENVIRONMENTS.size() - 1) {
                    throw reject(RejectReason.INVALID_REQUEST,
                            "版本已处于 PROD，没有更高环境可以提升");
                }
                targetIdx = highest + 1; // 全部为空时落到 DEV
                targetEnv = ENVIRONMENTS.get(targetIdx);
            }
            EnvironmentSlot target = slots.get(targetIdx);

            // 2) 确定要提升的版本，并校验必经环节没有被跳过。
            String version;
            if (targetIdx == 0) {
                if (requestedVersion != null) {
                    version = requestedVersion;
                } else if (def.getVersions().isEmpty()) {
                    throw reject(RejectReason.INVALID_REQUEST,
                            "流水线还没有任何版本，无法提升到 DEV");
                } else {
                    // 首次进 DEV 且未指定版本：取最新提交的版本；
                    // 它是否可用由后面的统一校验给出 VERSION_BLOCKED 等原因。
                    version = def.getVersions().get(def.getVersions().size() - 1).getVersion();
                }
            } else {
                EnvironmentSlot source = slots.get(targetIdx - 1);
                if (source.getCurrentVersion() == null) {
                    // 上游环境为空 = 跳过了必经环节。
                    throw recordRejection(target, RejectReason.SOURCE_ENVIRONMENT_EMPTY,
                            "环境 " + ENVIRONMENTS.get(targetIdx - 1) + " 尚未部署，"
                                    + "不能跳过它直接提升到 " + targetEnv);
                }
                if (requestedVersion != null && !requestedVersion.equals(source.getCurrentVersion())) {
                    throw recordRejection(target, RejectReason.PROMOTION_VERSION_MISMATCH,
                            "要提升的版本 " + requestedVersion + " 与上游环境 "
                                    + ENVIRONMENTS.get(targetIdx - 1) + " 当前版本 "
                                    + source.getCurrentVersion() + " 不一致");
                }
                version = source.getCurrentVersion();
            }

            // 3) 版本本身必须存在且可用。
            PipelineVersion pv = findVersion(def, version);
            if (pv.getStatus() == VersionStatus.BLOCKED) {
                throw recordRejection(target, RejectReason.VERSION_BLOCKED,
                        "版本 " + version + " 已被标记为不可用，不能提升到 " + targetEnv);
            }

            // 4) 目标环境不能已经是该版本（不允许悄悄放行/重复写入）。
            if (version.equals(target.getCurrentVersion())) {
                throw recordRejection(target, RejectReason.ALREADY_AT_ENVIRONMENT,
                        "环境 " + targetEnv + " 当前已经是版本 " + version);
            }

            // 5) 校验全部通过后一次性落库，不存在中间态。
            String previous = target.getCurrentVersion();
            Instant now = Instant.now();
            target.setPreviousVersion(previous);
            target.setCurrentVersion(version);
            target.setCurrentAt(now);
            target.setLastAction(DeploymentAction.PROMOTE);
            target.setLastRejection(null);
            target.setLastRejectionMessage(null);
            target.setLastRejectionAt(null);
            DeploymentRecord record = new DeploymentRecord(
                    "dep-" + deploymentSeq.incrementAndGet(), pipelineId, targetEnv,
                    version, DeploymentAction.PROMOTE, previous, now);
            target.getHistory().add(record);
            return record;
        }
    }

    public DeploymentRecord rollback(String pipelineId, RollbackRequest request) {
        PipelineDefinition def = getPipeline(pipelineId);
        if (request == null || isBlank(request.getEnvironment())) {
            throw reject(RejectReason.INVALID_REQUEST, "回滚环境不能为空");
        }
        int idx = envIndex(request.getEnvironment());
        synchronized (mutex) {
            EnvironmentSlot slot = slotsByPipeline.get(pipelineId).get(idx);
            String current = slot.getCurrentVersion();
            if (current == null) {
                throw recordRejection(slot, RejectReason.NO_ROLLBACK_TARGET,
                        "环境 " + slot.getEnvironment() + " 尚未部署过，无法回滚");
            }
            // 在该环境的部署历史里，从近到远找第一个「不同于当前版本且仍可用」的版本。
            String targetVersion = null;
            List<DeploymentRecord> history = slot.getHistory();
            for (int i = history.size() - 1; i >= 0; i--) {
                DeploymentRecord r = history.get(i);
                if (r.getVersion().equals(current)) {
                    continue;
                }
                PipelineVersion pv = findVersionOrNull(def, r.getVersion());
                if (pv != null && pv.getStatus() == VersionStatus.AVAILABLE) {
                    targetVersion = r.getVersion();
                    break;
                }
            }
            if (targetVersion == null) {
                throw recordRejection(slot, RejectReason.NO_ROLLBACK_TARGET,
                        "环境 " + slot.getEnvironment() + " 没有可回滚的历史可用版本");
            }
            Instant now = Instant.now();
            slot.setPreviousVersion(current);
            slot.setCurrentVersion(targetVersion);
            slot.setCurrentAt(now);
            slot.setLastAction(DeploymentAction.ROLLBACK);
            slot.setLastRejection(null);
            slot.setLastRejectionMessage(null);
            slot.setLastRejectionAt(null);
            DeploymentRecord record = new DeploymentRecord(
                    "dep-" + deploymentSeq.incrementAndGet(), pipelineId,
                    slot.getEnvironment(), targetVersion, DeploymentAction.ROLLBACK, current, now);
            history.add(record);
            return record;
        }
    }

    // ---------------------------------------------------------------------
    // 运行：触发 / 取消 / 重试
    // ---------------------------------------------------------------------

    public RunRecord triggerRun(String pipelineId, TriggerRunRequest request) {
        PipelineDefinition def = getPipeline(pipelineId);
        if (request == null || isBlank(request.getVersion())) {
            throw reject(RejectReason.INVALID_REQUEST, "运行版本不能为空");
        }
        String version = request.getVersion().trim();
        Integer failAtStep = request.getFailAtStep();
        Integer pauseBeforeStep = request.getPauseBeforeStep();
        synchronized (mutex) {
            findVersion(def, version); // 版本必须属于该流水线
            if (failAtStep != null && (failAtStep < 0 || failAtStep >= def.getStages().size())) {
                throw reject(RejectReason.INVALID_REQUEST,
                        "failAtStep 超出步骤范围 [0," + (def.getStages().size() - 1) + "]");
            }
            if (pauseBeforeStep != null
                    && (pauseBeforeStep < 0 || pauseBeforeStep >= def.getStages().size())) {
                throw reject(RejectReason.INVALID_REQUEST,
                        "pauseBeforeStep 超出步骤范围 [0," + (def.getStages().size() - 1) + "]");
            }
            RunRecord active = activeRun(pipelineId);
            if (active != null) {
                // 并发触发同一流水线：明确拒绝，状态不会串。
                throw reject(RejectReason.RUN_ALREADY_ACTIVE,
                        "流水线已有进行中的运行 " + active.getId() + "，请等待其结束");
            }
            RunRecord run = newRunRecord(def, version, null, failAtStep, pauseBeforeStep);
            schedule(run, def.getStages());
            return run;
        }
    }

    public RunRecord cancelRun(String runId) {
        synchronized (mutex) {
            RunRecord run = mustFindRun(runId);
            if (isTerminal(run.getStatus())) {
                throw reject(RejectReason.RUN_NOT_CANCELLABLE,
                        "运行 " + runId + " 已处于终态 " + run.getStatus() + "，不能取消");
            }
            // 先置取消标记，再在锁内把运行推入终态：
            // 执行线程之后的任何写入都会被终态守卫拦下，不会再冒出新结果。
            run.setCancelRequested(true);
            run.setStatus(RunStatus.CANCELLED);
            run.setFinishedAt(Instant.now());
            run.setFailureReason("运行被用户取消");
            for (RunStep step : run.getSteps()) {
                if (step.getStatus() != StepStatus.SUCCEEDED && step.getStatus() != StepStatus.FAILED) {
                    step.setStatus(StepStatus.CANCELLED);
                    if (step.getStartedAt() == null) {
                        step.setStartedAt(Instant.now());
                    }
                    if (step.getFinishedAt() == null) {
                        step.setFinishedAt(Instant.now());
                    }
                }
            }
            CountDownLatch gate = pauseGates.remove(runId);
            if (gate != null) {
                gate.countDown();
            }
            return run;
        }
    }

    public RunRecord retryRun(String runId) {
        synchronized (mutex) {
            RunRecord original = mustFindRun(runId);
            switch (original.getStatus()) {
                case SUCCEEDED:
                    throw reject(RejectReason.RETRY_NOT_ALLOWED,
                            "运行 " + runId + " 已成功，不允许重试");
                case PENDING:
                case RUNNING:
                    // 原运行（或任何运行）尚在进行：重试请求被明确拒绝，不产生重复运行。
                    throw reject(RejectReason.RETRY_ALREADY_ACTIVE,
                            "运行 " + runId + " 仍在进行中，不能重试");
                default:
                    break;
            }
            RunRecord active = activeRun(original.getPipelineId());
            if (active != null) {
                throw reject(RejectReason.RETRY_ALREADY_ACTIVE,
                        "流水线已有进行中的运行 " + active.getId() + "，不能重复重试");
            }
            PipelineDefinition def = getPipeline(original.getPipelineId());
            // 重试视为瞬时故障后的全新执行：不再继承失败注入点，
            // 但版本与来源关系保留，可通过 retryOfId 追溯。
            RunRecord retry = newRunRecord(def, original.getVersion(), original.getId(),
                    null, null);
            schedule(retry, def.getStages());
            return retry;
        }
    }

    public RunRecord resumeRun(String runId) {
        synchronized (mutex) {
            RunRecord run = mustFindRun(runId);
            CountDownLatch gate = pauseGates.remove(runId);
            if (gate != null) {
                gate.countDown();
            }
            run.setPauseBeforeStep(null);
            return run;
        }
    }

    public RunRecord getRun(String runId) {
        return mustFindRun(runId);
    }

    public List<RunRecord> listRuns(String pipelineId) {
        getPipeline(pipelineId);
        synchronized (mutex) {
            return List.copyOf(runsByPipeline.get(pipelineId));
        }
    }

    // ---------------------------------------------------------------------
    // 运行执行（后台线程）
    // ---------------------------------------------------------------------

    private void schedule(RunRecord run, List<String> stages) {
        // 调用方已持锁。
        executor.submit(() -> execute(run, stages));
    }

    private void execute(RunRecord run, List<String> stages) {
        try {
            synchronized (mutex) {
                if (run.getStatus() == RunStatus.CANCELLED) {
                    return;
                }
                run.setStatus(RunStatus.RUNNING);
                run.setStartedAt(Instant.now());
            }
            for (int i = 0; i < stages.size(); i++) {
                // 暂停点：等待放行，期间取消同样生效。
                CountDownLatch gate = pauseGates.get(run.getId());
                if (gate != null && run.getPauseBeforeStep() != null
                        && run.getPauseBeforeStep() == i) {
                    while (gate.getCount() > 0) {
                        if (run.isCancelRequested()) {
                            return; // 取消已在锁内完成终态落库
                        }
                        gate.await(50, TimeUnit.MILLISECONDS);
                    }
                    synchronized (mutex) {
                        run.setPauseBeforeStep(null);
                    }
                }

                synchronized (mutex) {
                    if (run.getStatus() == RunStatus.CANCELLED) {
                        return;
                    }
                    RunStep step = run.getSteps().get(i);
                    step.setStatus(StepStatus.RUNNING);
                    step.setStartedAt(Instant.now());
                }

                // 模拟步骤执行耗时。
                Thread.sleep(40);

                synchronized (mutex) {
                    if (run.getStatus() == RunStatus.CANCELLED) {
                        return;
                    }
                    RunStep step = run.getSteps().get(i);
                    Integer failAt = run.getFailAtStep();
                    if (failAt != null && failAt == i) {
                        step.setStatus(StepStatus.FAILED);
                        step.setFinishedAt(Instant.now());
                        step.setFailureReason("步骤[" + step.getName() + "]执行失败（模拟注入）");
                        for (int j = i + 1; j < stages.size(); j++) {
                            RunStep rest = run.getSteps().get(j);
                            rest.setStatus(StepStatus.CANCELLED);
                            rest.setStartedAt(Instant.now());
                            rest.setFinishedAt(Instant.now());
                        }
                        run.setStatus(RunStatus.FAILED);
                        run.setFailedStep(i);
                        run.setFailureReason("步骤 " + i + " (" + step.getName() + ") 执行失败");
                        run.setFinishedAt(Instant.now());
                        return;
                    }
                    step.setStatus(StepStatus.SUCCEEDED);
                    step.setFinishedAt(Instant.now());
                }
            }
            synchronized (mutex) {
                if (run.getStatus() == RunStatus.CANCELLED) {
                    return;
                }
                run.setStatus(RunStatus.SUCCEEDED);
                run.setFinishedAt(Instant.now());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (mutex) {
                if (!isTerminal(run.getStatus())) {
                    run.setStatus(RunStatus.CANCELLED);
                    run.setFinishedAt(Instant.now());
                    run.setFailureReason("运行线程被中断");
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    private RunRecord newRunRecord(PipelineDefinition def, String version, String retryOfId,
                                   Integer failAtStep, Integer pauseBeforeStep) {
        // 调用方已持锁。
        RunRecord run = new RunRecord(
                "run-" + runSeq.incrementAndGet(), def.getId(), version, retryOfId,
                failAtStep, pauseBeforeStep, Instant.now());
        int i = 0;
        for (String stage : def.getStages()) {
            run.getSteps().add(new RunStep(i++, stage));
        }
        runsById.put(run.getId(), run);
        runsByPipeline.get(def.getId()).add(run);
        if (pauseBeforeStep != null) {
            pauseGates.put(run.getId(), new CountDownLatch(1));
        }
        return run;
    }

    private RunRecord activeRun(String pipelineId) {
        // 调用方已持锁。
        RunRecord found = null;
        for (RunRecord r : runsByPipeline.get(pipelineId)) {
            if (!isTerminal(r.getStatus())) {
                found = r;
            }
        }
        return found;
    }

    private RunRecord mustFindRun(String runId) {
        RunRecord run = runsById.get(runId);
        if (run == null) {
            throw reject(RejectReason.RUN_NOT_FOUND, "运行不存在: " + runId);
        }
        return run;
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED;
    }

    private List<EnvironmentSlot> createSlots(String pipelineId) {
        List<EnvironmentSlot> list = new ArrayList<>();
        for (String env : ENVIRONMENTS) {
            list.add(new EnvironmentSlot(pipelineId, env));
        }
        return list;
    }

    private PipelineVersion findVersion(PipelineDefinition def, String version) {
        PipelineVersion v = findVersionOrNull(def, version);
        if (v == null) {
            throw reject(RejectReason.VERSION_NOT_FOUND,
                    "流水线 " + def.getId() + " 不存在版本 " + version);
        }
        return v;
    }

    private PipelineVersion findVersionOrNull(PipelineDefinition def, String version) {
        for (PipelineVersion v : def.getVersions()) {
            if (v.getVersion().equals(version)) {
                return v;
            }
        }
        return null;
    }

    /** 把拒绝原因落到环境槽位上，使拒绝原因可通过接口查询，随后抛出。 */
    private RejectedException recordRejection(EnvironmentSlot slot, RejectReason reason, String message) {
        slot.setLastRejection(reason);
        slot.setLastRejectionMessage(message);
        slot.setLastRejectionAt(Instant.now());
        return reject(reason, message);
    }

    private static int envIndex(String environment) {
        if (environment == null) {
            throw reject(RejectReason.INVALID_ENVIRONMENT, "环境不能为空");
        }
        String normalized = environment.trim().toUpperCase();
        int idx = ENVIRONMENTS.indexOf(normalized);
        if (idx < 0) {
            throw reject(RejectReason.INVALID_ENVIRONMENT,
                    "未知环境 " + environment + "，合法环境: " + ENVIRONMENTS);
        }
        return idx;
    }

    /** 支持 1~3 段纯数字点分版本，如 1、1.2、1.2.3。 */
    private static int[] parseVersion(String version) {
        if (version == null || !version.matches("\\d+(\\.\\d+){0,2}")) {
            throw reject(RejectReason.INVALID_VERSION,
                    "版本号格式非法: " + version + "，应为 1~3 段数字点分形式，如 1.2.3");
        }
        String[] parts = version.split("\\.");
        int[] parsed = new int[3];
        for (int i = 0;i < parts.length; i++) {
            parsed[i] = Integer.parseInt(parts[i]);
        }
        return parsed;
    }

    private static int compareVersion(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static RejectedException reject(RejectReason reason, String message) {
        return new RejectedException(reason, message);
    }
}
