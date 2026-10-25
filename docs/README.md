# 文档索引

本目录只存放可对外公开的工程文档。设计推演、评审记录、迭代计划等内部材料不在仓库内。

| 文档 | 内容 |
|------|------|
| [AI-AGENT-IMPLEMENTATION.md](AI-AGENT-IMPLEMENTATION.md) | Agent 子系统整体设计：工具网关、RAG、分层记忆、DAG、审批流、安全设计、测试与验证 |
| [AI-ROADMAP.md](AI-ROADMAP.md) | 智能体能力分期建设路线 |
| [AI-SCENARIOS.md](AI-SCENARIOS.md) | 业务侧 AI 场景拆解与优先级 |
| [ALERT-DESIGN.md](ALERT-DESIGN.md) | 库存与订单异常告警设计 |
| [RAG-真实评测报告.md](RAG-真实评测报告.md) | 99 条真实用例的检索与生成质量评测结果 |
| [库存流水与定时对账-实现说明.md](库存流水与定时对账-实现说明.md) | 单入口库存变更、条件原子更新、五项对账校验的实现细节 |
| [DEPLOYMENT.md](DEPLOYMENT.md) | 部署与运行说明 |
| [数据库迁移执行指南.md](数据库迁移执行指南.md) | Flyway 自动迁移与手工迁移脚本的执行方式 |
| [本地开发环境准备.md](本地开发环境准备.md) | 本地依赖（MySQL / Redis / PgVector）准备步骤 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |

## 其他目录

- `sql/`：手工迁移脚本（带守卫，可重复执行）
- `init-data/`：初始化数据
- `visualizations/`：架构思维导图与功能地图
- `qihang-erp.sql`：完整库结构与种子数据
