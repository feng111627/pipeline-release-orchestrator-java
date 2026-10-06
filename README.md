# Pipeline Release Orchestrator（本地内存版）

一个零外部依赖、本地即可运行的数据流水线发布编排服务。流水线、版本、环境、运行记录
全部保存在内存里（重启即清空），用于演练和验证**版本发布 → 跨环境提升 → 回滚**整套流程
在各种边界下是否给出一致、可复现的结果。

- Java 21 + Spring Boot 3.4，仅依赖 spring-boot-starter-web
- 所有状态变更都在同一个进程内锁上完成「校验 + 写入」，被拒绝时不会产生任何中间态
- 所有拒绝都带稳定的原因码（`reason`），可通过 HTTP 响应和环境查询接口查到

## 一、三块状态流转规则

### 1. 版本（Version）

版本号支持 1~3 段数字点分形式（`1`、`1.2`、`1.2.3`），按数值逐段比较大小。

```
提交新版本
  │
  ├─ 版本号格式非法                         → 拒绝 INVALID_VERSION (400)
  ├─ 该版本号已存在                         → 拒绝 VERSION_ALREADY_EXISTS (409)
  ├─ 版本号 ≤ 当前最新版本                   → 拒绝 VERSION_REGRESSION (409)
  └─ 通过 → 追加进版本历史（AVAILABLE）

            block 接口
 AVAILABLE ───────────────► BLOCKED（不可提升、不可作为回滚目标）
```

- 版本历史**只追加**：重复提交与版本回退都被拒绝，任何人不能覆盖已经产生的历史结论。
- `VERSION_ALREADY_EXISTS`（版本已存在）与 `VERSION_REGRESSION`（版本回退）是两个
  明确区分的原因码。
- `BLOCKED` 表示该版本被隔离（例如发现缺陷），此后任何环境都不能再提升它，回滚选目标时
  也会跳过它。

### 2. 环境（Environment）

环境顺序固定为：

```
DEV → TEST → STAGING → PROD
```

每条流水线在每个环境上有一个槽位（`currentVersion` + 部署历史 + 最近一次拒绝原因）。

**提升（Promote）规则：**

```
确定目标环境（显式指定，否则取「已部署最高环境」的下一个；全部为空时默认 DEV）
  │
  ├─ 目标环境未知                            → INVALID_ENVIRONMENT (400)
  ├─ 非 DEV 且上游环境为空（跳级）            → SOURCE_ENVIRONMENT_EMPTY (409)
  ├─ 指定版本 ≠ 上游环境当前版本              → PROMOTION_VERSION_MISMATCH (409)
  ├─ 版本不存在 / 已 BLOCKED                 → VERSION_NOT_FOUND (404) / VERSION_BLOCKED (409)
  ├─ 目标环境当前已是该版本                   → ALREADY_AT_ENVIRONMENT (409)
  └─ 通过 → 写入新 currentVersion，追加 PROMOTE 历史记录
```

- DEV 的首次提升：不指定版本时默认取**最新提交**的版本。
- TEST/STAGING/PROD 只能接收紧邻上游环境当前正在使用的版本——即不能跳级、不能拿
  别的版本越级。
- 所有校验先于写入完成；任何拒绝发生后环境状态不变，拒绝原因（`lastRejection` /
  `lastRejectionMessage` / `lastRejectionAt`）记录在目标环境槽位上，可通过
  `GET .../environments/{env}` 查到。

**回滚（Rollback）规则：**

```
POST .../rollback {"environment":"..."}
  │
  ├─ 环境从未部署                            → NO_ROLLBACK_TARGET (409)
  ├─ 历史中找不到「不同于当前且仍 AVAILABLE」的版本
  │                                          → NO_ROLLBACK_TARGET (409)
  └─ 找到 → 回滚到最近一次满足条件的版本，
            旧当前版本记入 previousVersion，追加 ROLLBACK 历史记录
```

- 回滚目标来自该环境**自己的部署历史**（最近优先），被 BLOCKED 的版本会被跳过。
- 回滚本身也是一条部署记录，因此可以在两个历史可用版本之间来回回滚。

### 3. 运行（Run）

一次运行按流水线定义的步骤依次执行（默认步骤：`CHECKOUT → BUILD → UNIT_TEST → PACKAGE`）。

```
触发 POST .../runs
  │
  ├─ 版本不属于该流水线 / failAtStep、pauseBeforeStep 越界
  │                                          → INVALID_REQUEST (400)
  ├─ 该流水线已有未结束运行                    → RUN_ALREADY_ACTIVE (409)
  └─ 创建 PENDING 记录并异步调度 → RUNNING
                                   ├─ 每步成功 → 全部完成 → SUCCEEDED（终态）
                                   ├─ 命中 failAtStep → FAILED（终态）
                                   │      failedStep/failureReason 记录失败点，
                                   │      后续步骤标记 CANCELLED
                                   └─ 被取消 → CANCELLED（终态）
```

- **并发触发不串状态**：同一条流水线同一时刻只允许一个非终态运行，第二个请求直接
  409，且不会创建任何运行记录。
- **取消（cancel）**：只允许作用于非终态运行（否则 `RUN_NOT_CANCELLABLE`）。取消先置
  标记并在锁内把运行落成 `CANCELLED` 终态，执行线程的每一步写入都有终态守卫，因此
  取消之后不会再冒出任何成功结果；暂停中的运行会被同步放行。
- **重试（retry）**：
  - 只允许对 `FAILED` / `CANCELLED` 的运行重试；对成功运行重试 →
    `RETRY_NOT_ALLOWED`；对进行中运行或流水线尚有进行中运行时重试 →
    `RETRY_ALREADY_ACTIVE`（重复请求被明确拒绝，绝不产生重复运行）。
  - 重试生成一条**新运行记录**，`retryOfId` 指向原记录，原记录保留不动；重试视为
    瞬时故障后的全新执行，不继承失败注入点。
- 每条运行记录都暴露每个步骤的状态/起止时间、`failedStep` 与 `failureReason`，
  能看出走到哪一步、因为什么失败。
- `pauseBeforeStep` / `resume` 仅用于本地和测试时制造确定性时序窗口（例如验证并发与
  取消），非业务必需。

## 二、HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/environments` | 查看固定环境顺序 |
| GET/POST | `/api/pipelines` | 流水线列表 / 创建（body: `id,name,description,stages?`） |
| GET | `/api/pipelines/{id}` | 流水线详情（含版本历史） |
| GET/POST | `/api/pipelines/{id}/versions` | 版本列表 / 提交（body: `version,commitRef?`） |
| POST | `/api/pipelines/{id}/versions/{v}/block` | 隔离版本 |
| GET | `/api/pipelines/{id}/environments[/{env}]` | 各环境槽位状态、历史、最近拒绝原因 |
| POST | `/api/pipelines/{id}/promote` | 提升（body: `version?,targetEnvironment?`） |
| POST | `/api/pipelines/{id}/rollback` | 回滚（body: `environment`） |
| POST | `/api/pipelines/{id}/runs` | 触发运行（body: `version,failAtStep?,pauseBeforeStep?`） |
| GET | `/api/pipelines/{id}/runs` | 该流水线全部运行记录 |
| GET | `/api/runs/{runId}` | 单次运行（含每一步状态、失败点、失败原因） |
| POST | `/api/runs/{runId}/cancel` | 取消运行 |
| POST | `/api/runs/{runId}/retry` | 失败/取消后重试 |
| POST | `/api/runs/{runId}/resume` | 放行暂停点（测试用） |

被拒绝时统一返回（状态码 400/404/409）：

```json
{
  "rejected": true,
  "reason": "VERSION_ALREADY_EXISTS",
  "message": "版本 1.0.0 已存在，不能重复提交",
  "at": "2026-10-06T07:00:00Z"
}
```

原因码一览：`INVALID_REQUEST`、`INVALID_VERSION`、`INVALID_ENVIRONMENT`、
`PIPELINE_NOT_FOUND`、`VERSION_NOT_FOUND`、`RUN_NOT_FOUND`、
`VERSION_ALREADY_EXISTS`、`VERSION_REGRESSION`、`VERSION_BLOCKED`、
`SOURCE_ENVIRONMENT_EMPTY`、`PROMOTION_VERSION_MISMATCH`、`ALREADY_AT_ENVIRONMENT`、
`NO_ROLLBACK_TARGET`、`RUN_ALREADY_ACTIVE`、`RUN_NOT_CANCELLABLE`、
`RETRY_NOT_ALLOWED`、`RETRY_ALREADY_ACTIVE`。

## 三、本地运行与验证

### 启动

```bash
mvn spring-boot:run
# 服务监听 http://localhost:8080
```

### 一条完整的手工演练

```bash
# 1. 建流水线、交版本
curl -s -X POST localhost:8080/api/pipelines -H 'Content-Type: application/json' \
  -d '{"id":"demo","name":"演示流水线"}'
curl -s -X POST localhost:8080/api/pipelines/demo/versions -H 'Content-Type: application/json' \
  -d '{"version":"1.0.0"}'
curl -s -X POST localhost:8080/api/pipelines/demo/versions -H 'Content-Type: application/json' \
  -d '{"version":"1.1.0"}'

# 2. 版本冲突：重复 -> VERSION_ALREADY_EXISTS；回退 -> VERSION_REGRESSION
curl -s -X POST localhost:8080/api/pipelines/demo/versions -H 'Content-Type: application/json' \
  -d '{"version":"1.1.0"}'
curl -s -X POST localhost:8080/api/pipelines/demo/versions -H 'Content-Type: application/json' \
  -d '{"version":"1.0.1"}'

# 3. 按序提升（默认版本=最新提交，默认目标=下一环境）
curl -s -X POST localhost:8080/api/pipelines/demo/promote -H 'Content-Type: application/json' -d '{}'
curl -s -X POST localhost:8080/api/pipelines/demo/promote -H 'Content-Type: application/json' \
  -d '{"targetEnvironment":"PROD"}'      # 跳级 -> SOURCE_ENVIRONMENT_EMPTY
curl -s localhost:8080/api/pipelines/demo/environments/PROD   # lastRejection 可查
curl -s -X POST localhost:8080/api/pipelines/demo/promote -H 'Content-Type: application/json' \
  -d '{"targetEnvironment":"TEST"}'

# 4. 回滚：在 DEV 放两个版本再回退
curl -s -X POST localhost:8080/api/pipelines/demo/promote -H 'Content-Type: application/json' \
  -d '{"version":"1.0.0","targetEnvironment":"DEV"}'   # 1.1.0 -> 1.0.0
curl -s -X POST localhost:8080/api/pipelines/demo/rollback -H 'Content-Type: application/json' \
  -d '{"environment":"DEV"}'                            # 回到最近可用的 1.1.0

# 5. 运行、失败注入、重试、取消
curl -s -X POST localhost:8080/api/pipelines/demo/runs -H 'Content-Type: application/json' \
  -d '{"version":"1.1.0","failAtStep":1}'              # 第 2 步失败
curl -s localhost:8080/api/pipelines/demo/runs          # 看 failedStep / failureReason
curl -s -X POST localhost:8080/api/runs/run-2/retry     # 对失败运行重试（id 以实际返回为准）
```

### 运行测试

```bash
mvn test
```

测试共 11 个场景（另有 Spring 上下文冒烟 1 个），日志里带 `[场景N]` / `[接口X]`
前缀，可以直接看出每个场景在验证什么、结论是否「=> 通过」：

- 服务层 `OrchestrationServiceTest`（7 个）：版本冲突（重复/回退/非法格式/历史保留）、
  跨环境提升（跳级/版本不符/重复/阻塞）、回滚（成功/无目标/旧版本被隔离）、
  并发触发与取消、失败可见与重试、进行中重复重试。
- 接口层 `ApiControllerTest`（4 个）：上述关键场景经 HTTP 的状态码与原因码、
  环境上的拒绝原因查询、运行步骤明细与取消后不冒新结果。

## 四、范围说明

- 内存存储、单实例、启动即空盘：定位为本地可复现的规则演练服务。
- 单进程内通过一把互斥锁保证状态一致；多实例部署不在范围内。
