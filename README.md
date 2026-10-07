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

### 4. 批量交付（Delivery）

在单环境提升之上，提供「按版本一口气交付到目标环境」的用法：指定流水线、版本与目标
环境，系统沿 `DEV -> TEST -> STAGING -> PROD` 的固定顺序把该版本一路送到目标环境。

**顺序规则：**

- 从 `DEV` 开始逐环境规划到目标环境，**不跳过任何中间环境**；目标环境之前的每个环境
  都会出现在计划里。
- 某个环境当前**已经是目标版本**时记为 `SKIP`（跳过、不产生新部署记录）；否则记为
  `PROMOTE`（从 `fromVersion` 变成目标版本）。
- 目标版本若停在比目标环境更远的环境、或中间环境是别的版本，按计划把沿途环境统一提升/
  切换到目标版本即可——顺序由「从 DEV 逐环境规划」天然保证，不存在跳级。

**整体成败（原子性）：**

```
POST .../deliveries {"version":"1.0.0","targetEnvironment":"PROD"}
  │
  ├─ 缺 version / targetEnvironment           → INVALID_REQUEST (400)
  ├─ 目标环境未知                              → INVALID_ENVIRONMENT (400)
  ├─ 版本不存在                                → VERSION_NOT_FOUND (404)
  ├─ 版本已 BLOCKED                            → VERSION_BLOCKED (409)
  ├─ 目标环境当前已是该版本（且无同键已生效交付）→ ALREADY_AT_ENVIRONMENT (409)
  └─ 规划通过 → 在同一把锁内逐环境一次性提交，生成交付记录与各环境 PROMOTE 历史
```

- **先规划、后提交**：规划阶段只读，任何一步不可行就**整体拒绝**，响应里带
  `failureEnvironment`（卡在哪个环境）与既有 `reason`/`message`（因为什么）。
- 拒绝发生在任何写入之前，因此**绝不会出现前几个环境已改、后面没改的半成品**：
  被拒后所有相关环境的 `currentVersion`、`previousVersion` 与部署历史都保持原样，
  也不产生交付记录。
- 拒绝原因码与单环境提升完全一致（`VERSION_BLOCKED` / `ALREADY_AT_ENVIRONMENT` 等），
  不引入新的拒绝语义。

**提交前预演（dry-run，绝不改状态）：**

`POST .../deliveries/preview` 返回本次交付的计划，**不修改任何环境、版本与历史**：

```json
{
  "dryRun": true,
  "deliverable": true,
  "pipelineId": "demo",
  "version": "1.0.0",
  "targetEnvironment": "PROD",
  "steps": [
    { "environment": "DEV",    "action": "SKIP",    "fromVersion": "1.0.0", "toVersion": "1.0.0" },
    { "environment": "TEST",   "action": "PROMOTE", "fromVersion": null,    "toVersion": "1.0.0" },
    { "environment": "STAGING","action": "PROMOTE", "fromVersion": null,    "toVersion": "1.0.0" },
    { "environment": "PROD",   "action": "PROMOTE", "fromVersion": null,    "toVersion": "1.0.0" }
  ]
}
```

注定跑不通时返回 HTTP 200 但 `deliverable=false`，并给出会卡住的环境、原因码、说明，
以及走到该步之前已规划出的 `steps`：

```json
{ "dryRun": true, "deliverable": false,
  "failureEnvironment": "DEV", "reason": "VERSION_BLOCKED", "message": "版本 1.0.0 已被标记为不可用……",
  "steps": [] }
```

> 参数层面非法（缺字段、未知环境）仍按普通请求错误返回 400，不属于预演计划的一部分。

**重复提交与并发（幂等收敛）：**

- 幂等键为 `(流水线, 版本, 目标环境)`。同一键**只有一次真正生效**的交付；之后的重复
  提交不再执行第二遍，而是收敛到**同一条交付记录**（HTTP 200，`reused=true`），不会新增
  任何环境变更或部署历史。首次生效返回 HTTP 201（`reused=false`）。
- 并发提交同一键时，所有请求在同一把进程锁内串行：只有一个请求执行并落库，其余请求拿到
  同一条记录（`reused=true`）。
- 注意区分：`ALREADY_AT_ENVIRONMENT` 只在「目标环境当前已是该版本、但不存在同键已生效
  交付」时出现（例如该状态是用单环境提升达成的）；一旦该键已经成功交付过，重复提交一律
  幂等收敛而不是再拒绝。
- 被拒绝的交付不占用幂等键，解除原因后可以重新提交。
- 已生效交付可通过 `GET .../deliveries` 与 `GET /api/deliveries/{id}` 查询；每个环境由此
  产生的部署历史记录带 `deliveryId`，可反查归属交付。

**与单环境提升 / 回滚的兼容范围：**

- 批量交付复用同一套环境顺序、版本可用性校验与「先校验后写入」的锁，不改变
  `POST .../promote`、`POST .../rollback` 的任何行为与拒绝原因。
- 批量交付在每个环境上产生的是常规 `PROMOTE` 部署记录（额外带 `deliveryId`），之后既可以
  继续单环境提升，也可以照常回滚；回滚规则不变。
- 单环境提升/回滚与批量交付共享同一份内存状态，混用是允许的：批量交付的规划总是基于
  当前最新状态重新计算。

## 二、HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/environments` | 查看固定环境顺序 |
| GET/POST | `/api/pipelines` | 流水线列表 / 创建（body: `id,name,description,stages?`） |
| GET | `/api/pipelines/{id}` | 流水线详情（含版本历史） |
| GET/POST | `/api/pipelines/{id}/versions` | 版本列表 / 提交（body: `version,commitRef?`） |
| POST | `/api/pipelines/{id}/versions/{v}/block` | 隔离版本 |
| GET | `/api/pipelines/{id}/environments[/{env}]` | 各环境槽位状态、历史、最近拒绝原因 |
| POST | `/api/pipelines/{id}/promote` | 单环境提升（body: `version?,targetEnvironment?`） |
| POST | `/api/pipelines/{id}/rollback` | 回滚（body: `environment`） |
| POST | `/api/pipelines/{id}/deliveries/preview` | 批量交付预演（body: `version,targetEnvironment`，不改状态） |
| POST | `/api/pipelines/{id}/deliveries` | 批量交付到目标环境（首次 201，重复复用 200 `reused=true`） |
| GET | `/api/pipelines/{id}/deliveries` | 该流水线全部批量交付记录 |
| GET | `/api/deliveries/{deliveryId}` | 单次批量交付（含各环境环节与部署记录 id） |
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
`PIPELINE_NOT_FOUND`、`VERSION_NOT_FOUND`、`RUN_NOT_FOUND`、`DELIVERY_NOT_FOUND`、
`VERSION_ALREADY_EXISTS`、`VERSION_REGRESSION`、`VERSION_BLOCKED`、
`SOURCE_ENVIRONMENT_EMPTY`、`PROMOTION_VERSION_MISMATCH`、`ALREADY_AT_ENVIRONMENT`、
`NO_ROLLBACK_TARGET`、`RUN_ALREADY_ACTIVE`、`RUN_NOT_CANCELLABLE`、
`RETRY_NOT_ALLOWED`、`RETRY_ALREADY_ACTIVE`。

批量交付被整体拒绝时，错误体在上述字段外额外给出 `failureEnvironment`：

```json
{
  "rejected": true,
  "reason": "VERSION_BLOCKED",
  "message": "版本 1.0.0 已被标记为不可用，不能交付到 DEV",
  "failureEnvironment": "DEV",
  "at": "2026-10-08T07:00:00Z"
}
```

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

### 批量交付演练（另建一条流水线，避免与上面的状态混淆）

```bash
# 1. 建流水线、交版本
curl -s -X POST localhost:8080/api/pipelines -H 'Content-Type: application/json' \
  -d '{"id":"rel"}'
curl -s -X POST localhost:8080/api/pipelines/rel/versions -H 'Content-Type: application/json' \
  -d '{"version":"3.0.0"}'

# 2. 先预演：dryRun=true，给出 DEV..PROD 四个 PROMOTE 环节，不改任何状态
curl -s -X POST localhost:8080/api/pipelines/rel/deliveries/preview \
  -H 'Content-Type: application/json' \
  -d '{"version":"3.0.0","targetEnvironment":"PROD"}'

# 3. 正式交付：首次 201，四个环境一口气全部生效
curl -i -s -X POST localhost:8080/api/pipelines/rel/deliveries \
  -H 'Content-Type: application/json' \
  -d '{"version":"3.0.0","targetEnvironment":"PROD"}'

# 4. 重复提交同一交付：200 + reused=true，收敛到同一条记录，不再产生部署历史
curl -i -s -X POST localhost:8080/api/pipelines/rel/deliveries \
  -H 'Content-Type: application/json' \
  -d '{"version":"3.0.0","targetEnvironment":"PROD"}'
curl -s localhost:8080/api/pipelines/rel/deliveries     # 只有一条交付记录

# 5. 整体被拒示例：隔离一个新版本后交付到 PROD，
#    409 + failureEnvironment=DEV + VERSION_BLOCKED，四环境状态与历史完全不变
curl -s -X POST localhost:8080/api/pipelines/rel/versions -H 'Content-Type: application/json' \
  -d '{"version":"3.1.0"}'
curl -s -X POST localhost:8080/api/pipelines/rel/versions/3.1.0/block
curl -i -s -X POST localhost:8080/api/pipelines/rel/deliveries \
  -H 'Content-Type: application/json' \
  -d '{"version":"3.1.0","targetEnvironment":"PROD"}'

# 6. 预演注定失败的交付：200 + deliverable=false，同样不改状态
curl -s -X POST localhost:8080/api/pipelines/rel/deliveries/preview \
  -H 'Content-Type: application/json' \
  -d '{"version":"3.1.0","targetEnvironment":"PROD"}'
```

### 运行测试

```bash
mvn test
```

测试共 26 个场景（另有 Spring 上下文冒烟 1 个），日志里带 `[场景N]` / `[接口X]` /
`[批量N]` 前缀，可以直接看出每个场景在验证什么、结论是否「=> 通过」：

- 服务层 `OrchestrationServiceTest`（7 个）：版本冲突（重复/回退/非法格式/历史保留）、
  跨环境提升（跳级/版本不符/重复/阻塞）、回滚（成功/无目标/旧版本被隔离）、
  并发触发与取消、失败可见与重试、进行中重复重试。
- 接口层 `ApiControllerTest`（7 个）：上述关键场景经 HTTP 的状态码与原因码、
  环境上的拒绝原因查询、运行步骤明细与取消后不冒新结果；以及批量交付的预演+正式交付、
  整体被拒（409 含 `failureEnvironment` 且状态不变）、重复提交 201→200 幂等收敛。
- 服务层 `BatchDeliveryServiceTest`（12 个）：
  - 顺利跑通：空盘一路到 PROD、部分环境已是目标版本时 SKIP；
  - 整体被拒不留痕：目标已在该版本、版本 BLOCKED、版本不存在，断言四个环境的当前版本与
    部署历史快照在拒绝前后完全一致，且不产生交付记录；
  - 预演不改状态：可通时给出各环境 from→to，不可通时给出被卡环境与原因；
  - 幂等与并发：同键重复提交收敛到同一条记录（`reused=true`），8 线程并发只有 1 次真正
    生效、其余复用同一结果，且各环境历史不增加；
  - 被拒后可重新交付、参数非法与交付记录查询。

## 四、范围说明

- 内存存储、单实例、启动即空盘：定位为本地可复现的规则演练服务。
- 单进程内通过一把互斥锁保证状态一致；多实例部署不在范围内。
