# 启航ERP AI Agent - 部署文档

> 本文档描述启航ERP AI Agent二次开发版本的本地开发环境搭建和启动流程。

---

## 一、环境要求

| 组件 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 17+ | 编译目标17，运行时推荐JDK 21 |
| Maven | 3.8+ | 构建工具 |
| MySQL | 8.0+ | 业务数据库 + Agent元数据 |
| Redis | 6.0+ | 分布式锁 + 缓存 + 会话记忆 |
| PostgreSQL | 14+ | 向量存储（可选，不启用则RAG降级） |
| pgvector | 0.5+ | PostgreSQL向量扩展（可选） |
| Docker | 20.10+ | 可选，用于快速启动依赖服务 |

---

## 二、快速启动（Docker Compose）

### 2.1 启动依赖服务

项目根目录提供 `docker-compose.yml`，可一键启动 PostgreSQL(pgvector) 和 Redis：

```bash
# 设置环境变量（Windows PowerShell）
$env:PGVECTOR_PASSWORD="你的PostgreSQL密码"
$env:REDIS_PASSWORD="你的Redis密码"

# 启动服务
docker-compose up -d

# 查看服务状态
docker-compose ps
```

> 注意：MySQL 建议使用本地安装或已有实例，docker-compose 中未包含 MySQL。

### 2.2 初始化 pgvector 扩展

PostgreSQL 启动后，需要手动创建 vector 扩展：

```bash
# 进入PostgreSQL容器
docker exec -it qihang-erp-pgvector psql -U postgres

# 在psql中执行
CREATE DATABASE erp_vector;
\c erp_vector
CREATE EXTENSION vector;
\q
```

---

## 三、手动环境搭建

### 3.1 MySQL 准备

```sql
-- 创建数据库
CREATE DATABASE `qihang-erp` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户（可选）
CREATE USER 'qihang'@'%' IDENTIFIED BY '你的密码';
GRANT ALL PRIVILEGES ON `qihang-erp`.* TO 'qihang'@'%';
FLUSH PRIVILEGES;
```

> Flyway 会在应用启动时自动执行 `db/migration` 下的所有 V1 脚本，创建 Agent 相关表结构。

### 3.2 Redis 准备

```bash
# 启动Redis（无密码，本地开发）
redis-server

# 或带密码启动
redis-server --requirepass 你的密码
```

### 3.3 PostgreSQL + pgvector 准备（可选）

如果不启用 PGVector，RAG 会自动降级为 MySQL FULLTEXT + BM25，应用可正常启动。

如需启用：
```bash
# 安装PostgreSQL后，创建数据库和扩展
createdb erp_vector
psql -d erp_vector -c "CREATE EXTENSION vector;"
```

### 3.4 本地 Embedding 模型（Ollama，推荐）

向量检索需要 Embedding 模型。默认走本地 Ollama，**不需要任何云 API Key**：

```bash
# 1. 安装并启动 Ollama（https://ollama.com）
# 2. 拉取中文效果较好的 embedding 模型（1024 维，与 vector_store 表一致）
ollama pull bge-m3
# 3. 验证（应返回 1024 维向量）
curl http://localhost:11434/api/embed -d '{"model":"bge-m3","input":"测试"}'
```

切换回阿里云百炼时：设 `EMBEDDING_PROVIDER=openai` + `OPENAI_EMBEDDING_ENABLED=true` + `DASHSCOPE_API_KEY`。

---

## 四、配置环境变量

### 4.1 必须设置的环境变量

所有密钥均通过环境变量注入，**切勿在 yml 配置文件中填写真实密钥**。

#### Windows PowerShell
```powershell
# MySQL
$env:MYSQL_URL="jdbc:mysql://127.0.0.1:3306/qihang-erp?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的MySQL密码"

# DeepSeek大模型
$env:DEEPSEEK_API_KEY="sk-你的DeepSeek密钥"

# JWT签名密钥（至少32位随机字符串）
$env:JWT_SECRET="你的JWT签名密钥"
```

#### Windows CMD
```cmd
set MYSQL_URL=jdbc:mysql://127.0.0.1:3306/qihang-erp?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8
set MYSQL_USERNAME=root
set MYSQL_PASSWORD=你的MySQL密码
set DEEPSEEK_API_KEY=sk-你的DeepSeek密钥
set JWT_SECRET=你的JWT签名密钥
```

#### Linux/Mac
```bash
export MYSQL_URL="jdbc:mysql://127.0.0.1:3306/qihang-erp?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8"
export MYSQL_USERNAME="root"
export MYSQL_PASSWORD="你的MySQL密码"
export DEEPSEEK_API_KEY="sk-你的DeepSeek密钥"
export JWT_SECRET="你的JWT签名密钥"
```

### 4.2 可选环境变量

```bash
# PostgreSQL向量数据库（启用RAG向量检索时需要）
export PGVECTOR_URL="jdbc:postgresql://localhost:5432/erp_vector"
export PGVECTOR_USERNAME="postgres"
export PGVECTOR_PASSWORD="你的PostgreSQL密码"

# 阿里云百炼DashScope（Embedding向量化，不设置则RAG降级）
export DASHSCOPE_API_KEY="sk-你的DashScope密钥"

# 本地 Embedding（推荐，默认走 Ollama，无需云 Key）
export EMBEDDING_PROVIDER="ollama"        # 想用百炼就设成 openai
export OLLAMA_BASE_URL="http://localhost:11434"
export OLLAMA_EMBEDDING_MODEL="bge-m3"    # 1024 维，与 vector_store 表维度一致

# 向量存储开关（默认 false；需要 RAG 向量检索时设为 true）
export AGENT_VECTOR_STORE_ENABLED="true"

# Redis密码（本地无密码可不设）
export REDIS_PASSWORD="你的Redis密码"

# OpenAI兼容API（可选）
export OPENAI_BASE_URL="https://api.deepseek.com"
export OPENAI_API_KEY="sk-你的密钥"
export OPENAI_MODEL="deepseek-chat"
```

### 4.3 使用 .env 文件（推荐）

在项目根目录创建 `.env` 文件（已加入 .gitignore，不会被提交）：

```env
# MySQL
MYSQL_URL=jdbc:mysql://127.0.0.1:3306/qihang-erp?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8
MYSQL_USERNAME=root
MYSQL_PASSWORD=你的MySQL密码

# DeepSeek
DEEPSEEK_API_KEY=sk-你的DeepSeek密钥

# JWT
JWT_SECRET=你的JWT签名密钥

# PostgreSQL（可选）
PGVECTOR_URL=jdbc:postgresql://localhost:5432/erp_vector
PGVECTOR_USERNAME=postgres
PGVECTOR_PASSWORD=你的PostgreSQL密码

# DashScope（可选）
DASHSCOPE_API_KEY=sk-你的DashScope密钥
```

> IDEA 中可安装 EnvFile 插件，启动时自动加载 .env 文件。

### 4.4 本机密钥文件（可选，推荐）

不想每次设置环境变量时，可以在**仓库根目录**创建 `application-local.yml`（已在 `.gitignore` 中，不会提交，也不会打进 jar）：

```yaml
spring:
  datasource:
    username: root
    password: 你的MySQL密码
  data:
    redis:
      password: 你的Redis密码

token:
  secret: 你的JWT密钥

agent:
  vector-store:
    enabled: true
    password: 你的PostgreSQL密码
```

`application-agent.yml` 通过 `spring.config.import: optional:file:./application-local.yml` 加载它；
部署环境没有该文件时自动忽略，继续使用环境变量。

---

## 五、构建与启动

### 5.1 编译项目

```bash
# 完整编译（跳过测试）
mvn clean compile -pl erp-api -am -DskipTests

# 编译并运行单元测试
mvn test -pl erp-api -am
```

### 5.2 启动应用

```bash
# 方式一：Maven启动
mvn spring-boot:run -pl erp-api

# 方式二：打包后启动
mvn clean package -DskipTests
java -jar erp-api/target/erp-api.jar

# 方式三：IDEA中直接运行 ErpApi.main()
```

### 5.3 验证启动成功

看到以下日志表示启动成功：
```
Tomcat started on port 6149 (http)
Started ErpApi in X.XXX seconds
```

访问健康检查：
```
http://localhost:6149/
```

### 5.4 监控端点（Micrometer / Prometheus）

工具调用指标同时写入 Micrometer，可通过 Actuator 暴露给 Prometheus：

```
http://localhost:6149/actuator/health      # 健康检查（匿名可访问）
http://localhost:6149/actuator/prometheus  # Prometheus 抓取（匿名可访问）
```

抓取到的关键指标：

| 指标 | 说明 |
|------|------|
| `agent_tool_calls_total{tool,result}` | 各工具调用次数（success/failure） |
| `agent_tool_duration_seconds{tool}` | 工具执行耗时（含 P50/P95/P99 分位） |
| `agent_tool_errors_total{tool,error}` | 按错误类型统计的失败次数 |

其余 actuator 端点（如 `/actuator/metrics`）仍需登录态访问，避免信息泄露。

Grafana 面板：导入 `erp-api/src/main/resources/grafana/agent-metrics-dashboard.json`，
在导入向导里选择 Prometheus 数据源即可。面板包含 8 项：全局成功率、平均执行耗时、
总调用次数、总失败次数、各工具调用量（成功/失败）、工具性能明细表、失败分布、各工具 P95 耗时。

### 5.5 测试

```bash
# 单元测试（默认，不需要外部依赖；CI 会跑这一档）
mvn -pl erp-api -am test

# 仅集成测试（需要本机 MySQL:127.0.0.1:3306 + Redis:127.0.0.1:6379）
mvn -pl erp-api -am test -Pintegration-tests

# 全量测试（单元 + 集成）
mvn -pl erp-api -am test -Pall-tests
```

集成测试的默认连接参数（与 `InventoryConcurrencyTest`/`RagEvaluationRealRunTest` 等一致）：

- MySQL：`127.0.0.1:3306`，库 `qihang-erp`，用户/口令从 `MYSQL_USERNAME`/`MYSQL_PASSWORD` 读取（未设置时回退 `root/1234`）
- Redis：`127.0.0.1:6379`，口令从 `REDIS_PASSWORD` 读取（未设置时无密码）
- PGVector：口令从 `PGVECTOR_PASSWORD` 读取（未设置时回退 `1234`）

```bash
export MYSQL_PASSWORD="你的MySQL密码"
export REDIS_PASSWORD="你的Redis密码"
export PGVECTOR_PASSWORD="你的PostgreSQL密码"
```

如果是全新环境，先准备基线数据再跑集成测试：

```bash
mysql -h127.0.0.1 -uroot -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 < docs/qihang-erp.sql
for f in $(ls erp-api/src/main/resources/db/migration/V1_*.sql | sort -V); do
  mysql -h127.0.0.1 -uroot -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 qihang-erp < "$f"
done
```

CI（`.github/workflows/ci.yml`）包含两个 Job：`unit-tests` 直接跑单元测试；
`integration-tests` 用 MySQL/Redis service container 建库并跑 `-Pall-tests`。

---

## 六、配置说明

### 6.1 Profile 说明

| Profile | 用途 |
|---------|------|
| dev | 开发环境默认配置 |
| agent | AI Agent 相关配置（RAG、记忆、评测等） |

默认激活：`dev, agent`

### 6.2 关键配置项

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| 服务端口 | - | 6149 | Tomcat HTTP端口 |
| MySQL地址 | MYSQL_URL | localhost:3306/qihang-erp | 业务数据库 |
| MySQL用户名 | MYSQL_USERNAME | root | - |
| MySQL密码 | MYSQL_PASSWORD | - | **必须设置** |
| DeepSeek密钥 | DEEPSEEK_API_KEY | - | **必须设置** |
| JWT密钥 | JWT_SECRET | - | **必须设置** |
| PGVector地址 | PGVECTOR_URL | localhost:5432/erp_vector | 可选 |
| PGVector密码 | PGVECTOR_PASSWORD | - | 启用向量检索时需要 |
| DashScope密钥 | DASHSCOPE_API_KEY | - | Embedding向量化，可选 |
| Redis密码 | REDIS_PASSWORD | - | 本地无密码可不设 |

### 6.3 RAG 向量存储开关

`application-agent.yml` 中：
```yaml
agent:
  vector-store:
    enabled: false  # true=启用PGVector，false=禁用并降级为MySQL全文+BM25
```

---

## 七、常见问题

### Q1: 启动报错 `Connection to localhost:5432 refused`

**原因**：PostgreSQL 未启动，或 `agent.vector-store.enabled=true` 但 PGVector 不可用。

**解决**：
- 方案一：启动 PostgreSQL + pgvector
- 方案二：设置 `agent.vector-store.enabled=false`，RAG 自动降级

### Q2: 启动报错 `RedisConnectionFailureException`

**原因**：Redis 未启动，或密码配置不匹配。

**解决**：启动 Redis，或检查 `REDIS_PASSWORD` 环境变量。本地 Redis 无密码时不要设置该变量。

### Q3: Flyway 迁移失败

**原因**：数据库中已有表结构但无 flyway_schema_history 记录。

**解决**：配置中已设置 `baseline-on-migrate=true`，会自动建立基线。如仍失败，可手动执行：
```sql
DELETE FROM flyway_schema_history WHERE success = 0;
```

### Q4: DeepSeek API 调用失败

**原因**：`DEEPSEEK_API_KEY` 未设置或已过期。

**解决**：检查环境变量，确认密钥有效。注意：**旧密钥如果曾经提交到Git，建议立即吊销并重新生成**。

### Q5: 单元测试跑不通

**原因**：部分测试需要真实数据库连接（集成测试 @Tag("it")）。

**解决**：默认跳过集成测试，只跑单元测试：
```bash
mvn test -pl erp-api -am
```

如需跑集成测试：
```bash
mvn test -pl erp-api -am -Pintegration-tests
```

---

## 八、开发工具推荐

| 工具 | 用途 |
|------|------|
| IntelliJ IDEA | 主力IDE，推荐安装 EnvFile 插件加载 .env |
| DBeaver / Navicat | 数据库管理工具 |
| Redis Desktop Manager | Redis 可视化 |
| Postman / Apifox | 接口调试 |
| Grafana | 监控面板（需自行配置数据源） |

---

## 九、生产部署注意事项

1. **密钥安全**：所有密钥通过环境变量或配置中心注入，严禁硬编码
2. **数据库连接池**：根据并发量调整 HikariCP 参数
3. **Redis**：生产环境必须设置密码，开启持久化
4. **日志**：配置日志级别和滚动策略，避免磁盘打满
5. **监控**：接入 Prometheus + Grafana，配置告警规则
6. **备份**：MySQL 和 PostgreSQL 定期备份
7. **Git历史**：部署前建议用 BFG Repo-Cleaner 清洗历史中的旧密钥
