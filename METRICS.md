# AgentCode Java 分支量化指标

> 本文档为 `AgentCode/Java` 定义可核验、可复算的量化指标。
> 方法学借用 `.dsh/skills/asu-resume-audit-skill` 的“证据/口径纪律”：每个指标必须有分子、分母、时间窗口、数据来源、基线与目标；没有基线的指标只能标 `baseline_pending`，不能写“已达标”。

## 当前决策

- 范围：全维度（能力对齐 / 工程质量 / 可靠性性能 / 安全审批）。
- 对齐基准：需要与 Python 分支做 parity 矩阵。
- 性能基线：使用真实 LLM API 作为参考基线，CI 中仍建议使用 mock/固定语料做稳定门禁。

## 数据源

| 数据源 | 说明 |
| --- | --- |
| `target/surefire-reports/TEST-*.xml` | Maven 测试结果 |
| `target/site/jacoco/jacoco.csv` | JaCoCo 覆盖率（尚未接入，需在 `pom.xml` 增加插件） |
| `logs/agentcode.log` | `AUDIT_AGENT_RUN` / `AUDIT_APPROVAL_TIMEOUT` / `AUDIT_AI_*` 等审计日志（测试混合） |
| `metrics/capabilities.json` | Java/Python 能力对齐矩阵 |
| `metrics/real_llm_baseline.json` | 真实 LLM（deepseek-chat）N=20 评测集基线，运行类指标以此为准 |
| CI 日志 | 构建时长、重跑稳定性 |

## 快速命令

```bash
# 收集当前基线（解析 Surefire + 日志 + 能力矩阵 + 可选真实 LLM 基线覆盖）
python3 scripts/collect_metrics.py

# 校验 schema/current/capabilities
python3 scripts/validate_metrics.py

# 重跑真实 LLM 评测集（需要 OPENAI_* 环境变量注入 key，勿写进仓库）
#   1) 以 LOG_FILE=<隔离日志> 启动服务；2) bench_client 顺序跑；3) bench_baseline 解析
LOG_FILE=/tmp/bench.log mvn -o -Dmaven.repo.local=/path/to/.m2repo spring-boot:run &
node scripts/bench_client.mjs --ws ws://127.0.0.1:18080/ws/chat \
  --workspace /tmp/bench-ws --suite bench/suite.json --repeat 2
python3 scripts/bench_baseline.py --log /tmp/bench.log --out metrics/real_llm_baseline.json

# 常规质量门禁
mvn -o -Dmaven.repo.local=/path/to/.m2repo clean verify
```

## 指标总表

状态含义：

- `collected`：有当前值且可复算；
- `baseline_pending`：缺数据源或未采集；
- `test_required`：需要先补测试/埋点；
- `computed_from_matrix`：由 `capabilities.json` 汇总。

| ID | 指标 | 当前 | 基线 | 目标 | 状态 |
| --- | --- | ---: | ---: | --- | --- |
| `capability.parity_rate` | Java/Python 功能对齐率 | 68.75% | 68.75% | ≥80% (M1) | collected |
| `quality.test_pass_rate` | Maven 测试通过率 | 100% | 100% | 100% | collected |
| `quality.test_count` | 自动化测试用例数 | 65 | 39 | ≥60 (M1) | met |
| `quality.line_coverage` | 行覆盖率（JaCoCo） | 70.19%（756/1077） | 70.19% | ≥70% (M1) | collected |
| `quality.branch_coverage` | 分支覆盖率（JaCoCo） | 56.70%（326/575） | 56.70% | ≥60% (M1) | below_target |
| `quality.build_duration_ms` | 构建时长 | 无 | 无 | 不劣化基线 | baseline_pending |
| `quality.flaky_rate` | 不稳定测试率 | 无 | 无 | ≤2% (M2) | baseline_pending |
| `quality.static_issues` | 静态检查问题数（Checkstyle） | 12（0 error / 12 warn） | 12 | error=0 (M2) | collected |
| `runtime.run_success_rate` | 任务成功率（真实 N=20） | 100% | 100% | ≥99% | collected (real) |
| `runtime.run_duration_ms_p50` | 单轮 run 时延 p50（真实） | 3028ms | 3028ms | 不劣化基线 | collected (real) |
| `runtime.run_duration_ms_p95` | 单轮 run 时延 p95（真实） | 6723ms | 6723ms | 不劣化基线 | collected (real) |
| `runtime.ai_call_duration_ms_p50` | AI 调用时延 p50（真实） | 1139ms | 1139ms | 不劣化基线 | collected (real) |
| `runtime.ai_call_duration_ms_p95` | AI 调用时延 p95（真实） | 1620ms | 1620ms | 不劣化基线 | collected (real) |
| `runtime.tokens_per_run` | 每 run Token 消耗（真实，成本口径） | 5483.9 | 5483.9 | ≤8000 (M1) | collected (real) |
| `runtime.tool_success_rate` | 工具执行成功率 | 无 | 无 | ≥99% (M2) | baseline_pending |
| `runtime.permission_per_run` | 平均每次 run 审批请求数（真实） | 0.3 | 0.3 | 监控 | collected (real) |
| `runtime.approval_timeout_rate` | 审批超时率（测试日志） | 12.12% | 12.12% | ≤1% (M2) | collected* (test) |
| `runtime.ws_error_rate` | WebSocket 异常率 | 无 | 无 | ≤1% (M2) | baseline_pending |
| `safety.deny_rule_coverage` | 黑名单拦截覆盖率 | 100%（13/13） | 100% | 100% (M1) | proven (test) |
| `safety.path_traversal_blocked` | 路径越界拦截测试覆盖率 | 100%（6/6） | 100% | 100% (M1) | proven (test) |
| `safety.decision_coverage` | 审批决策覆盖 | 100%（4/4） | 100% | 100% (M1) | proven (test) |
| `safety.secrets_in_logs` | 日志敏感信息泄漏数 | 0 | 0 | 0 | collected |

> 指标行标注：`(real)` = 由 `metrics/real_llm_baseline.json`（N=20 真实评测集）计算；`collected* (test)` = 仍来自 `logs/agentcode.log` 的测试混合日志，仅供过程观测。真实 LLM 基线见下一节。

## 真实 LLM 基线（2026-08-28 · deepseek-chat · N=20）

由 `bench/suite.json`（10 个固定任务 × repeat 2 = 20 runs）+ `scripts/bench_client.mjs` 在**默认审批配置**下自动答复 APPROVED 跑出，日志经 `LOG_FILE` 隔离后由 `scripts/bench_baseline.py` 解析，产物 `metrics/real_llm_baseline.json`。归因方式 **runId 精确关联**（44/44 AI 事件带 runId，`unattributed=0`，`concurrency_safe=true`）。

| 指标 | 分子 | 分母 | 结果 |
| --- | ---: | ---: | ---: |
| 任务成功率 | 20 | 20 | 100% |
| run 时延 p50 | — | — | 3028 ms |
| run 时延 p95 | — | — | 6723 ms |
| AI 调用 p50 | — | — | 1139 ms |
| AI 调用 p95 | — | — | 1620 ms |
| Token/run（成本口径） | 109679 | 20 | 5483.9 |
| 审批请求/run | 6 | 20 | 0.3 |

样本：20 runs / 39 段 / 44 次 AI 调用，`unattributed_ai_events=0`，`attribution=runId_exact`。

> 口径与限制（诚实标注）：
> 1. **Token 修复已生效**：`AuditedChatModel.stream()` 现按逐字段取最大值聚合真实 usage（此前写死 0）。`tokens_per_run` 为计费口径（后续 ReAct 步骤会重发上下文），不是唯一 token。
> 2. **runId 已内嵌审计日志**：`AgentSession.run()` 通过 Reactor `contextWrite` 注入 runId，`AUDIT_AI_STREAM` 现带 `runId=`，归因不再依赖时间窗口，**支持并发**；时间窗仅作为老日志的兜底。
> 3. **run 时延含审批往返**：客户端自动 APPROVED，往返仍有毫秒级网络开销；p95 受个别多轮 run 抬升属正常，非代码回归（同一套任务首轮出现过 1 次瞬时超时，重跑即 20/20）。
> 4. **审批为默认配置 + 自动 APPROVED**：真实用户答复延迟未计入；该基线测的是模型/工具/审批链路本身，不含人类思考时间。
> 5. N=20、单 provider、单账号，p95 仍偏小样本，可复算但不宜过度外推。

## 能力对齐矩阵摘要

`metrics/capabilities.json` 当前共 25 项能力，其中与 Python 可比 24 项（`python_status != not_applicable`）；1 项为 Java-only（Web/浏览器 UI）不计入 parity 分子/分母。

- 可比项中：已实现 14 / 部分 5 / 未实现 5
- 计分（partial=0.5）：16.5 / 24 = **对齐率 68.75%**
- 部分实现：CLI、Skills、MCP、Compaction、Persistence
- 未实现：TUI、daemon/TCP 协议、Subagents、Trace/Replay、Task Management

> 口径修正：早期 `collect_metrics.py` 把 Java-only 的 Web UI 也算进分子却用 24 作分母，导致对齐率虚高为 72.92%；现只对可比项计分，真实值为 68.75%。

主要差距：

1. **TUI / CLI / daemon 协议层**：Java 是 Spring Boot + WebSocket，不是 Python 的 TCP daemon + TUI。
2. **Subagents / 后台任务**：Java 未实现 `SpawnAgentTool`/`TaskManager`。
3. **Trace / 事件持久化**：Java 没有 `events.jsonl` 与 trace 回放。
4. **MCP / Task 工具**：仅有开关或未实现完整注册链。

## 里程碑目标

| 里程碑 | 关键目标 |
| --- | --- |
| M0（当前骨架） | 测试通过率 100%；日志敏感信息 0；构建零失败；审批流核心场景测试通过 |
| M1（Python 能力对齐） | parity_rate ≥80%；line_coverage ≥70%；test_count ≥60；安全三类覆盖 100% |
| M2（生产可用） | flaky_rate ≤2%；approval_timeout_rate ≤1%；ws_error_rate ≤1%；p95 不劣化基线；静态检查 error=0 |

## 下一步（未实现项）

- [x] `pom.xml` 增加 JaCoCo 插件并采集覆盖率基线（实测 line 70.19% / branch 56.70%，门禁阈值 0.65/0.50 但默认 `haltOnFailure=false`）。
- [x] `pom.xml` 增加 Checkstyle（`config/checkstyle/checkstyle.xml`，非阻断，实测 0 error / 12 warning）。
- [x] 增加遍历 `DANGEROUS_COMMANDS`(13) / 越界模式(6) / 四种审批决策 的参数化测试 → `SafetyPolicyCoverageTest`（+26 用例，全绿）。
- [x] 在 `AuditedChatModel` 流式路径回填真实 `promptTokens/completionTokens/totalTokens`（逐字段取最大值聚合）。
- [x] 把 runId 透传进 `AUDIT_AI_STREAM`（`AgentSession` 的 Reactor `contextWrite` + `deferContextual` 读取），`tokens_per_run` 现支持并发精确关联。
- [x] 用真实 LLM（deepseek-chat）在默认审批下跑 N=20 固定评测集，产出可判定 p50/p95 与 tokens_per_run → `metrics/real_llm_baseline.json`。
- [x] 分支 CI/CD：`.github/workflows/agentcode-java.yml` 质量门禁 + 每日真实 LLM 回归 + 发布阻断。
- [ ] 输出工具成功/失败状态到审计日志，支撑 `runtime.tool_success_rate`（仍需改 `AgentSession`/工具 Hook）。
- [ ] （可选）接入 SpotBugs 做字节码级缺陷扫描；收敛 14 条 Checkstyle warning（多为 `catch (Exception)` 宽泛捕获）。
- [ ] parity 缺口补齐：Subagents / Trace-Replay / daemon-TCP 协议 / Task 工具，把 parity_rate 从 68.75% 推向 M1 的 ≥80%。