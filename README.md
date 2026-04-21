# Spring AI Chat RAG Samples

Spring AI 构建的 RAG (检索增强生成) 示例项目，展示多种 RAG 模式和 AI 功能。

## 功能特性

### 聊天功能
- **基础聊天** - 支持对话记忆的 AI 聊天
- **RAG 聊天** - 基于向量数据库的检索增强生成
- **高级 RAG** - 带关键词重排序的 RAG
- **模块化 RAG** - 查询转换、扩展、多路检索
- **元数据过滤** - 基于来源的向量检索过滤

### 图像功能
- **图像分析** - 识别图像内容并返回描述
- **图像生成** - 根据文本生成图像
- **图像匹配** - 生成图像并匹配相似描述

## 技术栈

- **Java 21**
- **Spring Boot 4.0.5**
- **Spring AI 1.1.4**
- **Redis** (向量存储)
- **Maven** (构建工具)

## 快速开始

### 环境要求

使用 sdkman 管理环境

```bash
# 安装 sdkman (首次)
curl -s "https://get.sdkman.io" | bash

# 项目根目录会自动读取 .sdkmanrc 并切换版本
sdk env install

# 验证版本
java -version  # JDK 21
mvn -version   # Maven 3.9+
docker --version  # Docker (用于 Redis)
```

### 启动 Redis

```bash
docker run -d -p 6379:6379 redis:7-alpine
```

### 配置环境变量

```bash
export OPENAI_API_KEY=your-api-key
export REDIS_HOST=localhost
export REDIS_PORT=6379
```

### 构建并运行

```bash
# 构建项目
mvn clean package -DskipTests

# 运行应用
mvn spring-boot:run
```

应用启动后访问：http://localhost:8080

## API 端点

### 聊天 API

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/chat` | POST | 基础聊天 |
| `/api/chat/rag` | POST | RAG 聊天 |
| `/api/chat/advanced` | POST | 高级 RAG（带重排序） |
| `/api/chat/rag-modular` | POST | 模块化 RAG |
| `/api/chat/qa-template` | POST | 自定义模板 RAG |
| `/api/chat/filter` | POST | 带元数据过滤的聊天 |

### 图像 API

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/image/find/{object}` | GET | 查找包含指定对象的图像 |
| `/api/image/generate/{object}` | GET | 生成图像 |
| `/api/image/describe` | GET | 描述所有图像 |
| `/api/image/describe/{image}` | GET | 描述单个图像 |
| `/api/image/load` | GET | 加载图像描述到向量库 |
| `/api/image/generate-and-match/{object}` | GET | 生成图像并匹配 |

### RAG API

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/rag/embedding` | GET | 获取文本嵌入向量 |
| `/api/rag/embedding/similarity` | GET | 计算文本相似度 |
| `/api/rag/search` | GET | 向量检索 |
| `/api/rag/search/filter` | GET | 带元数据过滤的检索 |
| `/api/rag/query/translate` | GET | 查询翻译 |
| `/api/rag/query/compress` | GET | 查询压缩 |
| `/api/rag/query/rewrite` | GET | 查询改写 |
| `/api/rag/query/expand` | GET | 查询扩展 |
| `/api/rag/search/rerank` | GET | 结果重排序 |

## 使用示例

### 发送聊天请求

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "你好，请介绍一下自己"}'
```

### RAG 聊天

```bash
curl -X POST http://localhost:8080/api/chat/rag \
  -H "Content-Type: application/json" \
  -d '{"prompt": "这个项目的技术栈是什么？"}'
```

### 向量检索

```bash
curl "http://localhost:8080/api/rag/search?query=项目介绍&topK=5&threshold=0.5"
```

### 带过滤的检索

```bash
curl "http://localhost:8080/api/rag/search/filter?query=技术栈&source=about&highlight=true"
```

## 项目结构

```
src/
├── main/java/io/zhijun/ai/
│   ├── AiConfig.java           # AI 配置和文档加载
│   ├── ChatController.java     # 聊天端点
│   ├── ImageController.java    # 图像端点
│   ├── RagController.java      # RAG 端点
│   ├── ChatRagApplication.java # 应用入口
│   ├── model/                  # 数据模型
│   │   ├── ImageDescription.java
│   │   ├── Input.java
│   │   ├── Item.java
│   │   └── Output.java
│   └── util/                   # 工具类
│       ├── MarkdownHelper.java # Markdown 转 HTML
│       └── Utils.java          # BM25、相似度等工具
├── main/resources/
│   ├── application.yml         # 应用配置
│   └── data/                   # RAG 数据文件
│       ├── about.md
│       └── career.pdf
└── test/java/
    └── ChatRagApplicationTests.java
```

## Docker 部署

### 构建镜像

```bash
docker build -t spring-ai-chat-rag-samples:latest .
```

### 运行容器

```bash
docker run -d \
  -p 8080:8080 \
  -e OPENAI_API_KEY=your-key \
  -e REDIS_HOST=redis-host \
  -e REDIS_PORT=6379 \
  spring-ai-chat-rag-samples:latest
```

## CI/CD

项目配置了 GitHub Actions 自动化流水线：

- **CI 流水线**: 构建、测试、安全审计、Docker 构建
- **部署流水线**: 手动触发部署到 staging/production

详见 [CI_CD.md](CI_CD.md)

## 发布清单

发布前请查看 [LAUNCH_CHECKLIST.md](LAUNCH_CHECKLIST.md)

## 开发说明

### 运行测试

```bash
mvn test
```

### 代码格式化

```bash
mvn spotless:apply
```

### 依赖更新

```bash
mvn versions:display-dependency-updates
```

## 许可证

MIT License
