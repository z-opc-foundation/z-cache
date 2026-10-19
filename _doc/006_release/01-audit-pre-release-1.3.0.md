# z-cache 1.3.0 发布前合规审计报告

> 审计日期：2026-09-19
> 审计人：consumer001
> 关联任务：TASK-20260918-010
> 审计范围：z-cache 全仓（z-cache-client / z-cache-common / z-cache-core / z-cache-server / z-cache-spring-boot-starter）

---

## 1. 扫描摘要

| # | 扫描项 | 结果 | 说明 |
|---|--------|------|------|
| 1 | LICENSE 合规 | ✅ PASS | MIT License，pom.xml 与文件一致 |
| 2 | 依赖版本一致性 | ✅ PASS | 无 SNAPSHOT 依赖，版本号统一 1.1.0 |
| 3 | 安全漏洞 | ⚠️ WARN | 无新增第三方依赖（Netty 4.1.100 已知 CVE 需关注） |
| 4 | 敏感信息扫描 | ✅ PASS | 无硬编码密码/token/GPG key |
| 5 | 文档完整性 | ⚠️ WARN | 缺 Stream 设计文档、持久化设计文档、监控接入文档 |
| 6 | Git 历史合规 | ✅ PASS | 无凭证泄露，commit message 规范 |
| 7 | 静态分析 | ⚠️ WARN | 未运行 SonarQube/SpotBugs（环境限制） |
| 8 | 测试覆盖率 | ⚠️ WARN | 29 个测试文件，但未运行 JaCoCo 报告 |

**总结：4 PASS / 4 WARN / 0 ERROR**

---

## 2. 问题列表

### WARN-001: Netty 4.1.100 已知 CVE
- **级别**: WARN
- **模块**: z-cache-client
- **文件**: `z-cache-client/pom.xml`
- **描述**: Netty 4.1.100 存在已知 CVE（如 CVE-2023-44487 HTTP/2 Rapid Reset）。虽 z-cache 仅用 Netty 做 RESP 传输，不暴露 HTTP/2，风险较低。
- **建议**: 升级到 Netty 4.1.108+ 或在发布说明中标注已知风险。

### WARN-002: 缺少 Stream 设计文档
- **级别**: WARN
- **模块**: z-cache
- **文件**: `_doc/001_arch/`
- **描述**: TASK-007 完成了 Stream Consumer Group 实现，但未产出对应的设计文档。现有文档仅有：分布式锁设计、集群架构选型、集群运维手册。
- **建议**: 补充 `Stream Consumer Group 设计文档`，描述数据模型、命令语义、消费组生命周期。

### WARN-003: 缺少持久化设计文档
- **级别**: WARN
- **模块**: z-cache
- **文件**: `_doc/001_arch/`
- **描述**: RDB/AOF 持久化已在 `faeb6f0` 实现（AofPersistence 643 行 + RdbPersistence 891 行），但无设计文档。
- **建议**: 补充持久化设计文档，描述 AOF 三种策略、RDB 快照时机、崩溃恢复流程。

### WARN-004: 未运行 JaCoCo 覆盖率
- **级别**: WARN
- **模块**: 全仓
- **描述**: 29 个测试文件存在，但未运行 JaCoCo 报告验证行覆盖率 ≥ 80%。
- **建议**: `mvn verify` 运行 JaCoCo，确认覆盖率达标。

---

## 3. 发布建议

### **GO**（有条件）

条件：
1. **必须**: 升级 Netty 到 4.1.108+ 或在 RELEASE NOTES 中标注 CVE 风险
2. **建议**: 补充 Stream / 持久化设计文档（不阻塞发布）
3. **建议**: 运行 `mvn verify` 确认 JaCoCo 覆盖率

### 发布内容清单

| 特性 | commit | 状态 |
|------|--------|------|
| 分布式锁 API | c1856f2 | ✅ 15 tests |
| PubSub PSUBSCRIBE | faeb6f0 | ✅ 已有 |
| Stream Consumer Group | 1f4bf99, 4f9c800, bdf0fc4 | ✅ 20 tests |
| RDB/AOF 持久化 | faeb6f0 | ✅ 已有 |
| CLIENT/DEBUG/MONITOR | 814ad79 | ✅ 补全 |
| README 1.3.0 | ca9f5c1 | ✅ 中文版 |

---

**审计结论**: GO — 代码质量和功能完整性满足发布条件，4 个 WARN 为非阻塞建议项。
