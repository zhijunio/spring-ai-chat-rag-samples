# CI/CD and Automation

## Overview

This document describes the CI/CD pipeline and automation setup for the Spring AI Chat RAG Samples project.

## Pipeline Structure

```
Pull Request / Push to main
    │
    ▼
┌─────────────────────────┐
│   BUILD (Maven)          │  Compile source code
│   ↓ pass                 │
│   UNIT TESTS             │  Run JUnit tests
│   ↓ pass                 │
│   SECURITY AUDIT         │  OWASP dependency check (main only)
│   ↓ pass                 │
│   DOCKER BUILD           │  Tagged with pom.xml version + SHA
└─────────────────────────┘
    │
    ▼
  Ready for deployment
```

**Docker 标签策略：**
- CI 构建：`spring-ai-chat-rag-samples:<pom.xml version>` (如 `0.0.1-SNAPSHOT`)
- CI 构建：同时标记 commit SHA `spring-ai-chat-rag-samples:<sha>`
- 部署：使用 pom.xml 中的版本号

## Workflows

### 1. CI Pipeline (`.github/workflows/ci.yml`)

**Triggers:**
- Pull request to `main` branch
- Push to `main` branch

**Jobs:**
| Job | Description | Timeout |
|-----|-------------|---------|
| `build` | Maven compile, test, security audit | 15 min |
| `docker-build` | Build Docker image (main only) | 10 min |

**Services:**
- Redis (for vector store integration tests)

### 2. Deploy Pipeline (`.github/workflows/deploy.yml`)

**Triggers:**
- Manual workflow dispatch

**Inputs:**
| Parameter | Description | Required |
|-----------|-------------|----------|
| `version` | Version to deploy (e.g., v1.0.0) | Yes |
| `environment` | Target environment (staging/production) | Yes |

**Jobs:**
1. `deploy` - Build and deploy to target environment
2. `health-check` - Verify application health after deployment
3. `rollback` - Automatic rollback on failure

## Required Secrets

Configure these in GitHub Settings → Secrets and variables → Actions:

| Secret | Description | Required For |
|--------|-------------|--------------|
| `DEPLOY_TOKEN` | Deployment authentication token | Deploy workflow |
| `REDIS_HOST` | Redis server hostname | CI tests |
| `REDIS_PORT` | Redis server port | CI tests |

## Required Variables

Configure these in GitHub Settings → Variables:

| Variable | Description | Example |
|----------|-------------|---------|
| `APP_URL` | Application URL for health checks | `https://api.example.com` |

## Environments

Configure environments in GitHub Settings → Environments:

### Staging
- Auto-deploy after CI passes
- URL: `https://staging.example.com`

### Production
- Manual approval required
- URL: `https://api.example.com`

## Local Development

### Environment Setup with sdkman

```bash
# Install sdkman (first time only)
curl -s "https://get.sdkman.io" | bash

# Install and switch to project's Java and Maven versions
cd spring-ai-chat-rag-samples
sdk env install

# Verify current versions
sdk current
```

### Running Tests Locally

```bash
# Run all tests
mvn test

# Run with coverage
mvn test -Dcoverage

# Run specific test class
mvn test -Dtest=ChatRagApplicationTests
```

### Building Docker Image Locally

```bash
# Build image
docker build -t spring-ai-chat-rag-samples:latest .

# Run container
docker run -p 8080:8080 spring-ai-chat-rag-samples:latest

# Health check
curl http://localhost:8080/actuator/health
```

## Troubleshooting

### CI Build Fails

1. **Check the specific error message** in the GitHub Actions log
2. **Reproduce locally:**
   ```bash
   mvn clean compile
   mvn test
   ```
3. **Check Redis connection** for integration tests:
   ```bash
   docker run -d -p 6379:6379 redis:7-alpine
   ```

### Dependency Check Fails

If OWASP dependency check finds vulnerabilities:

1. Review the report in `target/dependency-check-report.html`
2. Update vulnerable dependencies:
   ```bash
   mvn versions:display-dependency-updates
   ```
3. If false positive, add suppression in `dependency-check-suppressions.xml`

### Docker Build Fails

1. **Check Java version** matches between CI and Dockerfile
2. **Clear Maven cache:**
   ```bash
   mvn clean
   ```
3. **Build locally to reproduce:**
   ```bash
   docker build --no-cache -t test-image .
   ```

## Monitoring

After deployment, monitor:

- **Health endpoint:** `/actuator/health`
- **Metrics endpoint:** `/actuator/metrics`
- **Logs:** Check container/cloud platform logs

## Rollback Procedure

If deployment fails health check:

1. **Automatic rollback** triggers via `rollback` job
2. **Manual rollback:**
   ```bash
   # Via GitHub CLI
   gh workflow run deploy.yml \
     -f version=<previous-version> \
     -f environment=production
   ```

## Future Improvements

- [ ] Add E2E tests with Testcontainers
- [ ] Configure Dependabot for automated dependency updates
- [ ] Add branch protection rules requiring CI pass
- [ ] Set up preview deployments for PRs
- [ ] Add performance tests to CI pipeline
