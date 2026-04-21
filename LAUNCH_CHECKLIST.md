# 发布清单 (Launch Checklist)

## 发布前检查 (Pre-Launch)

### 代码质量 ✅
- [x] 所有测试通过
- [ ] 构建成功无警告
- [x] Lint 检查通过
- [ ] 代码审查完成
- [ ] 无生产环境 TODO 注释
- [x] 错误处理覆盖

### 安全检查 ✅
- [x] 代码中无密钥
- [ ] `mvn dependency-check:check` 无严重漏洞
- [ ] 输入验证配置
- [x] Cookie 安全标志 (Secure, HttpOnly)
- [x] URL 验证 (HTTPS only)
- [ ] 速率限制配置

### 性能检查 ⚠️
- [ ] 无 N+1 查询
- [ ] 数据库索引配置
- [ ] 缓存配置
- [ ] 文档大小限制 (truncateDoc)

### 基础设施 ⚠️
- [ ] 环境变量配置
- [ ] Redis 连接配置
- [ ] OpenAI API 密钥配置
- [ ] Docker 镜像构建
- [ ] SSL/TLS 配置

### 文档 ✅
- [x] README 更新
- [x] API 文档
- [x] CI/CD 文档
- [x] 代码审查记录

## 功能开关策略 (Feature Flags)

### 当前功能开关

| 功能 | 开关名称 | 状态 | 负责人 | 过期日期 |
|------|----------|------|--------|----------|
| 高级 RAG | `advanced_rag` | 默认关闭 | - | - |
| 模块化 RAG | `modular_rag` | 默认关闭 | - | - |
| 图像分析 | `image_analysis` | 默认开启 | - | - |

### 建议添加的开关

```java
// 示例：RAG 功能开关
if (featureFlags.isEnabled("advanced_rag", userId)) {
    return advancedRagChatClient.prompt(input);
}
return basicRagChatClient.prompt(input);
```

## 分阶段发布计划 (Staged Rollout)

### 发布顺序

```
1. 部署到 Staging
   └── 完整测试套件
   └── 手动冒烟测试

2. 部署到 Production (功能关闭)
   └── 健康检查
   └── 错误监控

3. 团队内部测试 (24 小时)
   └── 团队使用
   └── 监控错误率

4. 金丝雀发布 5% (24-48 小时)
   └── 监控指标
   └── 对比基准

5. 逐步增加 (25% → 50% → 100%)
   └── 每步监控
   └── 可回滚

6. 完全发布
   └── 监控 1 周
   └── 清理功能开关
```

### 发布决策阈值

| 指标 | 继续 (green) | 调查 (yellow) | 回滚 (red) |
|------|--------------|---------------|------------|
| 错误率 | <10% 增长 | 10-100% 增长 | >2x 基准 |
| P95 延迟 | <20% 增长 | 20-50% 增长 | >50% 增长 |
| 客户端错误 | 无新错误类型 | <0.1% 会话 | >0.1% 会话 |

## 监控和可观测性 (Monitoring)

### 监控指标

**应用指标:**
- [ ] 错误率（按端点）
- [ ] 响应时间 (p50, p95, p99)
- [ ] 请求量
- [ ] 活跃用户数
- [ ] RAG 检索成功率

**基础设施指标:**
- [ ] CPU/内存使用率
- [ ] Redis 连接池
- [ ] 磁盘空间
- [ ] 网络延迟

**客户端指标:**
- [ ] API 错误率
- [ ] 页面加载时间

### 健康检查端点

```bash
# 基础健康检查
GET /actuator/health

# 详细健康检查
GET /actuator/health/detail

# 指标
GET /actuator/metrics
```

### 发布后验证 (1 小时内)

- [ ] 健康检查返回 200
- [ ] 错误监控无新增错误
- [ ] 延迟无回归
- [ ] 关键用户流程正常
- [ ] 日志正常流入
- [ ] 回滚机制验证

## 回滚计划 (Rollback Plan)

### 触发条件

立即回滚如果:
- 错误率 > 2x 基准
- P95 延迟 > 50% 增长
- 用户报告严重问题
- 数据完整性问题
- 安全漏洞

### 回滚步骤

**功能开关回滚 (<1 分钟):**
```bash
# 禁用功能开关
curl -X POST $FEATURE_FLAG_API/disable/{flag_name}
```

**部署回滚 (<5 分钟):**
```bash
# GitHub CLI 触发回滚
gh workflow run deploy.yml \
  -f environment=production \
  -f version=<previous-version>
```

**数据库回滚 (<15 分钟):**
```bash
# 如果有数据迁移
mvn flyway:undo -Dflyway.undoTargets=<previous>
```

### 回滚测试清单

- [ ] 回滚脚本已测试
- [ ] 数据库回滚已验证
- [ ] 团队通知流程确认

## 环境配置

### 环境变量

| 变量 | Staging | Production |
|------|---------|------------|
| `OPENAI_API_KEY` | ✅ 配置 | ✅ 配置 |
| `REDIS_HOST` | `localhost` | `redis.production.svc` |
| `REDIS_PORT` | `6379` | `6379` |
| `SERVER_PORT` | `8080` | `8080` |

### Docker 部署

```bash
# Staging
docker run -d \
  -p 8080:8080 \
  -e OPENAI_API_KEY=xxx \
  -e REDIS_HOST=redis-staging \
  spring-ai-chat-rag-samples:0.0.1-SNAPSHOT

# Production (使用具体版本号)
docker run -d \
  -p 8080:8080 \
  -e OPENAI_API_KEY=xxx \
  -e REDIS_HOST=redis-production \
  spring-ai-chat-rag-samples:1.0.0
```

## 发布沟通

### 发布前通知

- [ ] 团队通知 (Slack/邮件)
- [ ] 利益相关者通知
- [ ] 支持团队准备

### 发布后通知

- [ ] 发布成功通知
- [ ] 功能文档更新
- [ ] 用户通知 (如适用)

## 发布后任务

### 第一周

- [ ] 每日监控指标审查
- [ ] 收集用户反馈
- [ ] 记录任何问题

### 第二周

- [ ] 功能开关清理计划
- [ ] 性能基准更新
- [ ] 经验教训总结

## 附录

### 关键端点列表

| 端点 | 方法 | 用途 |
|------|------|------|
| `/api/chat` | POST | 基础聊天 |
| `/api/chat/rag` | POST | RAG 聊天 |
| `/api/image/find/{object}` | GET | 图像查找 |
| `/api/rag/search` | GET | 向量检索 |
| `/actuator/health` | GET | 健康检查 |

### 紧急联系人

| 角色 | 姓名 | 联系方式 |
|------|------|----------|
| 发布负责人 | - | - |
| 技术负责人 | - | - |
| 产品负责人 | - | - |

**最后更新:** 2026-04-21
**下次审查:** 发布后 1 周
