# Week6-7 持久化分层记忆 · 完成报告（Task 19-23）

> 对应方案《功能更新与实施路径分析》第二阶段 Week6-7「持久化分层记忆」
> 基线：Week4-6（提交 `2d179a8c`）
> 执行人：Doubao（临时接手 Claude Code）
> 日期：2026-10-12

---

## 一、接手前的烂摊子核查与处置

Claude Code 未产生新提交，所有改动停留在未提交区（共 56 条目）。核查结论：

| 问题 | 具体内容 | 处置 |
|------|----------|------|
| 破坏基线能力 | 删除通知模块 5 文件（`NotifyController` + `notify/` 钉钉/飞书/企微/NotifierService） | 已 `git restore` 全部恢复 |
| 范围越界 | 越界去做 audit(5)、tenant(3)、policy registry(4)、Redis 分布式锁(2)、临时脚本等 | 21 个 untracked 移至备份区，tracked 改动全部撤销 |
| 记忆本体几乎无推进 | MySQL 持久化仍是 TODO；Redis 只存 3 个字段、反序列化丢 layer/merchantId，读取必空指针 | 本次重写修复 |
| 倒退写法 | 把 `MemoryEntry` 的 `@Data` 展开成 287 行手写 getter/setter | 已还原为 `@Data` |

**全部 Claude 未提交工作已完整备份**（可随时取回）：

```
C:\Users\30378\Doubao\chats\2026-09-21\new-chat-1\claude-week67-backup\
├─ tracked.patch      # 全部 tracked 改动
├─ manifest.txt       # 56 条目清单
├─ files\             # 修改/新增文件的工作区版本
└─ removed\           # 从工作区移出的 21 个 untracked
```

> 其中 Claude 新增的 `RedisDistributedLock`（SETNX+Lua）代码质量尚可，但属 Week1-2 范畴，本次不纳入，已备份保留。

---

## 二、四层记忆设计

| 层级 | TTL | 作用域 | 存储介质 | 典型内容 |
|------|-----|--------|----------|----------|
| WORKING 工作记忆 | 1 小时 | 单次对话 | 进程内存（ConcurrentHashMap） | 刚提到的订单号 |
| SESSION 会话记忆 | 1 天 | 一次会话 | Redis | 本次会话问了 3 次退款 |
| USER 用户记忆 | 30 天 | 单个用户 | MySQL 长期 + Redis 缓存 | 用户姓名、偏好 |
| TEAM 团队记忆 | 90 天 | 整个商户 | MySQL 长期 + Redis 缓存 | 公司退款政策，商户内共享 |

**scopeId 约定**（决定隔离边界，调用时请遵循）：

- WORKING / SESSION：`scopeId = 会话ID`（如 `sess_abc123`）
- USER：`scopeId = "user_" + userId`（每个用户独立）
- TEAM：`scopeId = "merchant_" + merchantId`（同商户成员共享）

记忆主键 `memory_id` 格式：`层名:scopeId:key`，如 `USER:user_1:name`。

---

## 三、文件清单

### 新增

| 文件 | 说明 |
|------|------|
| `erp-api/.../agent/memory/MemoryRepository.java` | 记忆 MySQL 持久化仓库（JdbcTemplate）：upsert / findById / deleteById / deleteExpired |
| `erp-api/src/test/java/cn/qihangerp/agent/memory/LayeredMemoryTest.java` | 分层记忆单元测试，10 用例 |

### 修改

| 文件 | 说明 |
|------|------|
| `erp-api/.../agent/memory/MemoryManager.java` | 重写：Jackson 完整序列化、接入 Repository、修复租户隔离空指针、写入即脱敏 |
| `erp-api/.../agent/rag/RagEvaluator.java` | 修复 Week4-6 遗留编译硬伤：补 `import lombok.Data` |

### 保留未改（基线质量良好）

`MemoryLayer`（四层枚举）、`MemoryEntry`（@Data 实体）、`MemoryController`（REST 接口）、`SensitiveDataMasker`（脱敏工具）、Flyway 建表脚本 `V1_13_0__create_agent_memory_tables.sql`。

---

## 四、关键实现说明

1. **MySQL 持久化（Task 21）**：`MemoryRepository` 以 `memory_id` 唯一键做
   `INSERT ... ON DUPLICATE KEY UPDATE`（upsert），重复记忆自动覆盖；`deleteExpired` 清理过期长期记忆。
2. **表结构以 Flyway `V1_13_0` 为权威**。记忆键列名是 MySQL 保留字 `` `key` ``，Repository 中用反引号引用；本轮一度新建的重复 DDL `sql/agent_memory.sql` 已删除，避免两份结构不一致。
3. **Redis 序列化（Task 19/20）**：用 Jackson 把**整条 MemoryEntry** 序列化为 JSON（含 layer/merchantId/scope 等全部字段），替换原来手撸、只存 3 字段的写法。
   - `jackson-datatype-jsr310` 在本工程是 **runtime** 作用域，编译期不可直接 import；改用
     `new ObjectMapper().findAndRegisterModules()` 通过 SPI 自动注册时间模块，正确处理 `LocalDateTime`。
4. **跨用户隔离（Task 22/23）**：读取统一用 `Objects.equals(entry.getMerchantId(), merchantId)` 比较，
   不匹配即拦截返回 null，同时修掉了原代码 `entry.getMerchantId().equals(...)` 在 merchantId 缺失时的空指针。
5. **敏感字段脱敏（Task 23）**：任何记忆在落库/入缓存前统一过 `SensitiveDataMasker.autoMask`，
   手机 `138****1234`、身份证 `110101******1234`、银行卡 `6222 **** **** 1234`、邮箱 `abc***@example.com`。

---

## 五、验证情况

| 项 | 结果 |
|----|------|
| 多模块编译（erp-api 及依赖） | BUILD SUCCESS（JDK 21 / Spring Boot 4.1） |
| `LayeredMemoryTest` | **10/10 通过**（0 Failure / 0 Error） |
| 通知模块 | 已恢复，未被破坏 |
| 工作区 git status | 仅含本次 Week6-7 相关改动 |

单元测试覆盖（不依赖外部 Redis/MySQL）：记住名字、跨租户不可见、不存在返回 null、过期判定、记忆ID格式、四类脱敏、手机号无明文。

---

## 六、SESSION / USER / TEAM 手动接口验证（需启动 Redis + MySQL）

> 单元测试只覆盖走进程内存的 WORKING。以下用 REST 接口验证落 Redis / MySQL 的三层。
> 前置：启动 Redis(6379)、MySQL(3306)，表由 Flyway 自动建（或手动执行 `V1_13_0`）。

**1. 存 USER 记忆**

```http
POST /api/agent/memory/store
Content-Type: application/json

{"layer":"USER","scopeId":"user_1","key":"name","value":"我叫张三","merchantId":1001,"userId":1}
```

**2. 取回（应得到「我叫张三」）**

```
GET /api/agent/memory/retrieve?layer=USER&scopeId=user_1&key=name&merchantId=1001
```

**3. 跨租户隔离（merchantId 改为 2002，应 success=false）**

```
GET /api/agent/memory/retrieve?layer=USER&scopeId=user_1&key=name&merchantId=2002
```

**4. TEAM 共享 / 隔离**：`layer=TEAM, scopeId=merchant_1001` 存「7天无理由」，
同商户(1001)成员都能取到；`merchant_2002` 取不到。

**5. 脱敏**：存 `value="我的手机号13812341234"`，取回应为 `我的手机号138****1234`，无明文。

**6. SESSION 重启可恢复**：`layer=SESSION, scopeId=sess_1` 存入后重启服务，仍能从 Redis 取回。

**7. WORKING 易失**：`layer=WORKING` 存入后重启服务即丢失；TTL 1 小时。

---

## 七、后续待办

- 定时任务调度 `cleanupExpiredMemories()`（方案建议每日凌晨）。
- 将 `application-agent.yml` 的 Redis/DataSource/agent 配置正式合并进主 `application.yml`（目前为独立"待合并"文件）。
- 访问统计（access_count / last_accessed_at）如需精确落库，可在读取命中后异步回写；当前不强制。
- 后续 Week 的 audit / tenant / policy registry 如要实施，可从本次备份 `claude-week67-backup/removed/` 取回参考，但需评审后再纳入。
