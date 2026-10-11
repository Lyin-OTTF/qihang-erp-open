# 更新日志（Changelog）

> 本文件记录启航 ERP「Agent 工程魔改方案」的迭代进度，对应方案《功能更新与实施路径分析》中的阶段与任务编号。

---

## [Week2-4] 2026-10-11 — 可信执行边界 + 评测闭环

**对应方案进度**：第一阶段 Week2-3「工具权限与审计系统」+ 第二阶段 Week3-4「评测闭环」
**里程碑**：Agent 工具调用正式纳入权限治理与指标监控，评测集数据入库

### 新增功能

#### 1. 工具权限与审计基础（受限自治层 · Task 5）

| 文件 | 说明 |
|------|------|
| `erp-api/.../agent/policy/AutonomyLevel.java` | 自治等级枚举（OBSERVE / SUGGEST / DRAFT / EXECUTE / ESCALATE 五级） |
| `erp-api/.../agent/policy/ToolPolicy.java` | 工具策略注解（声明等级、分类、是否写操作） |
| `erp-api/.../agent/gateway/ToolGateway.java` | **工具调用网关核心**：权限检查、租户隔离（merchantId 强制注入）、审计占位、ESCALATE 人工确认、并发控制 |
| `erp-api/.../agent/gateway/ToolExecutionContext.java` | Tool 执行上下文（用户 / 租户 / 工具元信息） |
| `erp-api/src/test/java/cn/qihangerp/agent/gateway/ToolGatewayTest.java` | 网关单元测试，9 个用例 100% 通过 |

> 网关设计要点：所有 AI Tool 调用统一走 ToolGateway，不能绕过策略直接调 Service；ESCALATE 级操作（取消订单、退款确认等高危动作）必须进入人工确认流程。

#### 2. 评测闭环（第二阶段 · Task 10 / 11）

| 文件 | 说明 |
|------|------|
| `erp-api/.../agent/evaluation/MetricsCollector.java` | 指标收集器：成功率 / 响应时间（平均、最小、最大）/ 调用次数 / 错误类型统计，兼容 Prometheus/Grafana |
| `erp-api/.../agent/evaluation/MetricsController.java` | 指标查询接口 |
| `erp-api/src/main/resources/db/migration/V1_11_0__create_agent_rag_evaluation_table.sql` | RAG 评测集表（含 category / difficulty / tags 维度） |
| `erp-api/src/main/resources/db/migration/V1_11_1__insert_agent_rag_evaluation_data.sql` | **100 条评测数据**（订单查询 30 / 退款流程 20 / 订单状态 15 / 库存管理 15 / 商品查询 10 / 采购管理 10） |

#### 3. 其他

- `erp-api/pom.xml`：新增 JUnit 5 测试依赖（`junit-jupiter 5.10.0`）
- `vue3/start-front.bat`：前端一键启动脚本

### 变更与说明

- `ToolGateway.java` 已集成 `MetricsCollector`，工具调用指标自动采集
- 数据库迁移脚本沿用 `V1_11_x` 版本命名，置于 `erp-api/src/main/resources/db/migration/`

### 验证情况

| 项 | 结果 |
|----|------|
| ToolGateway 单元测试 | ✅ 9/9 通过 |
| MetricsCollector | ⚠️ 核心逻辑已实现，测试用例待补充 |
| 100 条评测集数据 | ✅ 已入库（脚本） |

### 下一迭代待办（对应方案后续任务）

- Task 6-9：PolicyRegistry 策略注册表、TenantProjector 租户投影完善、ActionLedger 审计账本落地、ApprovalManager 审批流（前端确认弹窗 + 后端审批接口）
- Task 12-13：Trace 链路追踪、Bad Case 反馈收集机制
- 11 个 AI Tool 类逐一接入 `@ToolPolicy` 注解并改造为走网关

---

## [Week1-2] — 第一阶段：启航 ERP 核心后端加固

**对应方案进度**：第一阶段 Week1-2「启航 ERP 核心后端加固」
（详见提交 `2e6aae84`：状态机建模、乐观锁 version 字段、分布式锁、幂等键设计）
