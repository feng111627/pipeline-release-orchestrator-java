# Pipeline Release Orchestrator

本地可运行的数据流水线发布编排服务。所有状态（流水线版本、环境部署、运行记录）都保存在本地内存中，通过 REST 接口对外暴露；任何非法操作都会被明确拒绝并返回稳定的原因码，绝不留下中间态。

## 运行与验证

```bash
# 需要 JDK 21+
mvn spring-boot:run        # 启动服务，默认 8080 端口
mvn test                   # 跑全部测试（20 个，日志中带 [场景]/[通过] 标记）
```

## 核心概念与状态流转

### 1. 流水线版本（PipelineDefinition）

每次提交一个版本会记录 `name + version + stages(环境序列) + 提交时间`，历史永久保留、不可覆盖。

- 版本号必须**严格大于**当前最新版本：
  - 等于已有版本 → `409 VERSION_ALREADY_EXISTS`
  - 小于最新版本 → `409 VERSION_REGRESSION`
  - stages 为空 → `409 EMPTY_STAGES`
- 历史接口永远返回全部版本，已产生的结论不会被改写。

### 2. 环境提升与回滚（EnvironmentState）

每个 `(流水线, 环境)` 维护 `currentVersion + 部署历史`。历史中的每条记录含 `version + action(PROMOTE/ROLLBACK) + 时间`。

**提升规则**（任一不满足即整体拒绝，环境状态不变）：

| 规则 | 违反时的原因码 |
|---|---|
| 版本已注册 | `VERSION_NOT_FOUND` |
| 目标环境在该版本的 stages 里 | `UNKNOWN_ENVIRONMENT` |
| 同环境内版本必须向前 | `ENV_VERSION_NOT_FORWARD` |
| 非首环境的前置环境当前必须是同一版本 | `STAGE_SKIPPED` |

**回滚规则**：回滚到该环境部署历史中最近一次**不同于当前版本**的版本（因此在 v1↔v2 之间连续回滚会来回切换）；没有可回滚目标 → `NO_ROLLBACK_TARGET`；从未部署过的环境 → `UNKNOWN_ENVIRONMENT`。

### 3. 运行（PipelineRun）

状态机：`QUEUED → RUNNING → SUCCEEDED / FAILED / CANCELLED`，`QUEUED` 也可直接 `CANCELLED`。终态不可再迁移（CAS 保证并发下只有一个迁移获胜）。

- **触发**：必须带 `idempotencyKey`（否则 `MISSING_IDEMPOTENCY_KEY`）。同一 `(流水线, 幂等键)` 并发触发只产生一条运行，其余请求拿到同一条运行记录。
- **执行**：异步逐 stage 推进，每步落一条 `StepRecord(stage, outcome, at)`；`failAtStage` 可模拟某 stage 失败，失败后 `failureReason` 可查。
- **取消**：仅 `QUEUED/RUNNING` 可取消；取消后不再产生任何新步骤，也不会再变成 `SUCCEEDED`；终态取消 → `RUN_ALREADY_FINISHED`。
- **重试**：仅 `FAILED` 可重试（否则 `RUN_NOT_RETRYABLE`）；对同一条失败运行的重复重试**幂等**，返回同一条新运行。

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/pipelines/{name}/versions` | 提交版本 `{"version":1,"stages":["dev","staging","prod"]}` |
| GET | `/api/pipelines/{name}/versions` | 查询全部历史版本 |
| POST | `/api/pipelines/{name}/promote` | 提升 `{"version":1,"environment":"dev"}` |
| POST | `/api/pipelines/{name}/environments/{env}/rollback` | 回滚到最近可用版本 |
| GET | `/api/pipelines/{name}/environments` | 查询各环境当前版本与历史 |
| POST | `/api/pipelines/{name}/runs` | 触发运行 `{"version":1,"idempotencyKey":"k","failAtStage":null}` |
| POST | `/api/runs/{runId}/cancel` | 取消运行 |
| POST | `/api/runs/{runId}/retry` | 重试失败运行（幂等） |
| GET | `/api/runs/{runId}` | 查询运行状态、步骤、失败原因 |
| GET | `/api/pipelines/{name}/runs` | 查询流水线全部运行 |

所有业务拒绝统一返回 `409`：

```json
{"reasonCode":"STAGE_SKIPPED","message":"cannot promote version 1 to 'prod': previous stage 'dev' is at version none","at":"..."}
```

## 本地手动验证示例

```bash
# 1. 提交两个版本
curl -s -XPOST localhost:8080/api/pipelines/deploy/versions -H 'Content-Type: application/json' -d '{"version":1,"stages":["dev","staging","prod"]}'
curl -s -XPOST localhost:8080/api/pipelines/deploy/versions -H 'Content-Type: application/json' -d '{"version":2,"stages":["dev","staging","prod"]}'

# 2. 重复提交 -> VERSION_ALREADY_EXISTS；提交 v1 -> VERSION_REGRESSION
curl -s -XPOST localhost:8080/api/pipelines/deploy/versions -H 'Content-Type: application/json' -d '{"version":1,"stages":["dev"]}'

# 3. 跳级提升被拒 -> STAGE_SKIPPED
curl -s -XPOST localhost:8080/api/pipelines/deploy/promote -H 'Content-Type: application/json' -d '{"version":1,"environment":"prod"}'

# 4. 按序提升 + 回滚
curl -s -XPOST localhost:8080/api/pipelines/deploy/promote -H 'Content-Type: application/json' -d '{"version":1,"environment":"dev"}'
curl -s -XPOST localhost:8080/api/pipelines/deploy/promote -H 'Content-Type: application/json' -d '{"version":2,"environment":"dev"}'
curl -s -XPOST localhost:8080/api/pipelines/deploy/environments/dev/rollback   # 回到 v1

# 5. 触发一次会在 staging 失败的运行，然后查询、重试
curl -s -XPOST localhost:8080/api/pipelines/deploy/runs -H 'Content-Type: application/json' -d '{"version":1,"idempotencyKey":"r1","failAtStage":"staging"}'
curl -s localhost:8080/api/pipelines/deploy/runs          # 看状态/步骤/失败原因
curl -s -XPOST localhost:8080/api/runs/<runId>/retry      # 幂等重试
```

## 测试覆盖

| 测试类 | 覆盖场景 |
|---|---|
| `VersioningTest` | 重复版本、版本回退、历史保留、缺失版本 |
| `PromotionRollbackTest` | 跳级、顺序提升、前置版本不一致、环境内倒退、回滚切换、无回滚目标 |
| `RunConcurrencyTest` | 同键并发触发收敛、异键并发隔离、取消后无新结果、失败重试幂等、非法触发 |
| `ApiIntegrationTest` | 拒绝原因码与状态通过 HTTP 接口可查 |

测试日志中带 `[场景]` / `[通过]` 标记，可直接看出每个用例在验证什么、是否通过。
