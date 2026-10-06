# 本地安全事件接入、检测与告警闭环服务

基于 Spring Boot 3.4 / Java 21 实现的纯本地 SIEM 小闭环：外部原始日志经 **HTTP 上报 → 归一化校验 →
规则匹配（阈值/序列）→ 抑制合并 → 告警生命周期管理**，全程使用本地 JSON 文件持久化，
不依赖数据库、消息队列、凭据或任何联网组件。

## 1. 设计要点：可解释、可复现

| 场景 | 保证机制 |
| --- | --- |
| 重复上报 | 归一化后以 `eventId` 幂等去重；重复记录明确返回 `DUPLICATE`，不计数、不重新检测 |
| 乱序 / 迟到 | 检测始终基于**全量事件按 `(eventTime, eventId)` 排序后重算**，与上报到达顺序无关 |
| 并发 | 引擎内单把 `ReentrantLock` 串行化“入库-检测”；告警流转用 `version` 乐观锁，冲突返回 409 |
| 重放一致 | 命中指纹 `sha256(ruleId + 排序后事件id)`、告警 id `sha256(ruleId + dedupKey)` 均为确定性派生 |
| 可解释 | 拒绝原因码、告警 `occurrences`（FIRST/NEW/SUPPRESSED 说明）、`timeline`（每次动作）全部落盘 |

## 2. 数据模型

### 2.1 原始事件与归一化事件

原始上报字段：`eventId`、`eventType`、`sourceIp`、`timestamp` 为必填，`target`、`username`
与任意自定义字段可选。归一化后生成 `SecurityEvent`：

- `eventId`：上报方提供的全局唯一标识（幂等键）
- `eventTime`：按事件时间统一解析（`Instant`，UTC）
- `receivedAt`：服务端接收时间（仅用于区分迟到，不参与窗口判定）
- 时间戳支持：10 位 epoch 秒、13 位 epoch 毫秒、ISO-8601（`2023-11-14T22:13:20Z`）、
  `yyyy-MM-dd HH:mm:ss`、`yyyy/MM/dd HH:mm:ss`（本地格式按 UTC 解释）

归一化拒绝原因码（绝不静默丢弃，批量逐条返回明细与下标）：

| reasonCode | 含义 |
| --- | --- |
| `MISSING_FIELD` / `BLANK_FIELD` | 必填字段缺失 / 只有空白 |
| `INVALID_TIMESTAMP` | 时间戳无法解析（含无法判定精度的纯数字） |
| `FUTURE_TIMESTAMP` | 事件时间领先当前 5 分钟以上（时钟错误脏数据） |
| `INVALID_SOURCE_IP` | sourceIp 不是合法 IPv4/IPv6（纯字符串校验，无 DNS） |
| `EMPTY_RECORD` / `MALFORMED_JSON` | 空记录 / 请求体非法 JSON |

### 2.2 规则模型

```json
{
  "ruleId": "R-BRUTE-FORCE",
  "type": "THRESHOLD",
  "eventTypes": ["LOGIN_FAILURE"],
  "threshold": 3,
  "windowSeconds": 300,
  "suppressSeconds": 600,
  "groupBy": ["sourceIp"],
  "severity": "HIGH",
  "enabled": true
}
```

- **THRESHOLD 阈值规则**：同一 `groupBy` 分组内，在 `windowSeconds` 滑动窗口中统计 `eventTypes`
  计数，达到 `threshold` 产生一次命中；命中批次出窗重新累计（每个事件最多参与一次命中，结果确定）。
- **SEQUENCE 序列规则**：`sequence` 给出有序事件类型链（内置规则为
  `LOGIN_FAILURE → LOGIN_FAILURE → LOGIN_SUCCESS`），相邻事件时间差不超过 `windowSeconds`
  且 `groupBy`（默认 `sourceIp + username`）相同才构成跨事件序列命中。
- 规则内置两条（暴力破解阈值、失败后成功序列），可经 HTTP 调整；每次更新 `version` 自增，
  规则调整后**立即对全量历史事件重放**。

### 2.3 抑制语义与告警模型

- 命中以 `dedupKey = ruleId | 分组键` 归并；告警 id = `sha256(ruleId + "|" + dedupKey)` 的
  16 位十六进制前缀——同一规则同一分组**永远只有一条告警**。
- 同一 dedupKey 的相邻命中：若命中时间差 `≤ suppressSeconds`，标记 `SUPPRESSED`
  合并进当前告警（不另开）；`>` 抑制窗口则标记 `NEW_BLOCK` 作为新命中块，仍记在同一条告警上。
- 告警 `occurrences` 记录每个命中块的证据事件 id、时间与合并/抑制原因；`timeline`
  记录 `CREATED / SUPPRESSED / REPLAY_RECONCILED / ACK / CLOSE / REOPENED_BY_MATCH` 等全部动作。

### 2.4 状态流转

```
OPEN ──ACK──▶ ACKNOWLEDGED ──UNACK──▶ OPEN
  │                  │
  └────CLOSE─────────┴──▶ CLOSED ──REOPEN(人工)──▶ OPEN
                              ▲
                              └── 抑制窗口外再次命中：引擎自动 REOPENED_BY_MATCH（同一条告警）
```

- 非法流转返回 `422 ILLEGAL_TRANSITION` 并列出当前状态允许的操作。
- 并发更新：请求可带 `expectedVersion`（乐观锁），不匹配返回 `409 VERSION_CONFLICT`；
  不带版本时由引擎串行化，每次成功流转 `version + 1`，状态不会丢失或被覆盖。

## 3. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/events` | 单条上报 |
| POST | `/api/events/batch` | 批量上报，body：`{"events":[...]}` |
| GET | `/api/events`、`/api/events/{id}` | 查询归一化事件（按事件时间排序） |
| GET | `/api/rules` | 规则列表 |
| POST | `/api/rules` | 新增/调整规则（调整后立即重放历史事件） |
| POST | `/api/rules/{ruleId}/replay` | 显式重放，结论必须与调整后一致 |
| GET | `/api/alerts?status=OPEN` | 告警列表（可按状态过滤） |
| GET | `/api/alerts/{id}` | 告警详情（命中块 + timeline） |
| POST | `/api/alerts/{id}/transition` | 流转：`{"action":"ACK|UNACK|CLOSE|REOPEN","operator":"...","expectedVersion":1}` |

上报响应示例：

```json
{
  "accepted": 1, "rejected": 0, "duplicate": 0,
  "acceptedEvents": [{
    "eventId": "n3", "status": "ACCEPTED",
    "detection": {"createdAlertIds": ["..."], "mergedAlertIds": [],
                  "reopenedAlertIds": [], "suppressedByAlertIds": []}
  }],
  "errors": []
}
```

## 4. 本地验证

环境要求：JDK 21+、Maven 3.9+（依赖均可用本地缓存，无需联网）。

```bash
# 运行全部测试（日志中可见 [业务:xxx] 用例[...] 结果=PASS/FAIL 的覆盖清单）
mvn test

# 打包并启动（默认 8080，数据目录 ./data）
mvn spring-boot:run

# 端到端冒烟
curl -X POST localhost:8080/api/events/batch -H 'Content-Type: application/json' -d '{
  "events": [
    {"eventId":"e1","eventType":"LOGIN_FAILURE","sourceIp":"10.0.0.1","timestamp":"1700000000"},
    {"eventId":"e2","eventType":"LOGIN_FAILURE","sourceIp":"10.0.0.1","timestamp":"1700000010"},
    {"eventId":"e3","eventType":"LOGIN_FAILURE","sourceIp":"10.0.0.1","timestamp":"1700000020"}
  ]}'
curl localhost:8080/api/alerts
```

持久化文件：`data/events.json`、`data/rules.json`、`data/alerts.json`（临时文件 + 原子移动写入）。
删除 `data/` 即可回到全新状态。测试使用独立临时数据目录，并通过仅测试配置开启的
`POST /api/admin/reset`（`siem.reset-enabled=true`，生产默认关闭）保证用例隔离。

## 5. 测试覆盖（共 16 个用例）

| 测试类（业务） | 覆盖场景 |
| --- | --- |
| `MalformedIngestTest` | 缺字段/空白、非法时间戳多形态、多合法时间格式、非法 IP、空记录、非法 JSON |
| `DuplicateIngestTest` | 跨请求与同批次重复 eventId：DUPLICATE 计数、无重复告警 |
| `OutOfOrderAndLateTest` | 阈值第 3 条迟到补齐；序列成功先到、失败迟到仍识别且只一次 |
| `SuppressionTest` | 抑制窗口内 SUPPRESSED 合并、窗口外 NEW_BLOCK、跨分组不合并 |
| `AlertLifecycleTest` | 合法流转全链路、非法流转拒绝、关闭后再次命中自动重开同一条 |
| `ConcurrentUpdateTest` | 8 线程并发流转仅一次成功 + 乐观锁；10 线程并发重复上报只入库一次 |
| `RuleReplayConsistencyTest` | 阈值 3→2→10 调整重放、连续重放结论稳定、确定性 id 不变 |

测试日志由 `BusinessLogExtension` 统一输出，形如：
`[业务:SuppressionTest] 用例[...] 结果=PASS`，并在每个业务结束时汇总 PASS/FAIL 数。
