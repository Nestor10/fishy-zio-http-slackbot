# Release Guide

## Overview

This project uses:
- **sbt-release** for semantic versioning and automated releases
- **sbt-native-packager** for Docker image generation
- **GitHub Actions** for CI/CD with multi-arch builds (AMD64 + ARM64)
- **Quay.io** as the container registry

## Prerequisites

### 1. Set Up Quay.io Repository

1. Create a repository at [quay.io](https://quay.io):
   - Repository name: `nestor10/fishy-zio-http-slackbot`
   - Visibility: Public or Private

2. Create a robot account or use personal credentials:
   - Go to Account Settings → Robot Accounts
   - Create new robot account with "Write" permission to the repository
   - Note the username and token

### 2. Configure GitHub Secrets

Add the following secrets to your GitHub repository:

1. Go to: Settings → Secrets and variables → Actions → New repository secret

2. Add `QUAY_USERNAME`:
   - Name: `QUAY_USERNAME`
   - Value: Your Quay.io username or robot account name (e.g., `nestor10+robot`)

3. Add `QUAY_PASSWORD`:
   - Name: `QUAY_PASSWORD`
   - Value: Your Quay.io password or robot account token

## Release Process

### Automated Release (Recommended)

The release process is fully automated via `sbt release`:

```bash
# Interactive release (asks for version numbers)
sbt release

# Non-interactive with defaults (bumps last version part)
sbt release with-defaults

# Manual version specification
sbt "release release-version 0.1.0 next-version 0.2.0-SNAPSHOT"
```

**What happens:**
1. ✅ Checks for snapshot dependencies
2. ✅ Runs tests
3. ✅ Updates `version.sbt` to release version (e.g., `0.1.0`)
4. ✅ Commits the version change
5. ✅ Creates git tag (e.g., `v0.1.0`)
6. ✅ Publishes Docker image locally (single-arch for verification)
7. ✅ Updates `version.sbt` to next snapshot (e.g., `0.2.0-SNAPSHOT`)
8. ✅ Commits the next version
9. ✅ Pushes commits and tags to GitHub

**After push:**
10. 🚀 GitHub Actions triggers on `v*` tag
11. 🏗️ Builds multi-arch Docker images (AMD64 + ARM64)
12. 📦 Pushes to Quay.io with version tag + `latest`

### Manual Docker Build (Testing)

For local testing before release:

```bash
# Build single-arch image locally (uses podman)
sbt Docker/publishLocal

# Check the image
podman images | grep fishy-zio-http-slackbot

# Test the image
podman run --rm \
  -e APP_SLACK_APP_TOKEN="xapp-..." \
  -e APP_SLACK_BOT_TOKEN="xoxb-..." \
  -e APP_LLM_BASE_URL="http://host.containers.internal:11434" \
  quay.io/nestor10/fishy-zio-http-slackbot:0.1.0-SNAPSHOT
```

## Versioning Strategy

This project uses **semantic versioning** (SemVer):

- **MAJOR** (1.0.0): Breaking changes
- **MINOR** (0.1.0): New features, backward-compatible
- **PATCH** (0.0.1): Bug fixes, backward-compatible

**Current:** `0.1.0-SNAPSHOT` (pre-release development)

**First release:** `0.1.0` (MVP production-ready)

**Next versions:**
- `0.2.0` - New features (e.g., analytics processor)
- `0.1.1` - Bug fixes to 0.1.0
- `1.0.0` - Stable production API

## Docker Image Details

### Multi-Architecture Support

Images are built for:
- `linux/amd64` - x86_64 servers (AWS EC2, GCP, most cloud VMs)
- `linux/arm64` - ARM servers (Apple Silicon, AWS Graviton, Raspberry Pi)

### Base Image

- **Base:** `eclipse-temurin:23-jre-noble`
- **OS:** Ubuntu 24.04 LTS (Noble Numbat)
- **Java:** Eclipse Temurin JRE 23
- **Size:** Multi-stage build minimizes final image size

### Image Tags

Every release creates two tags:
- `quay.io/nestor10/fishy-zio-http-slackbot:0.1.0` - Version-specific (immutable)
- `quay.io/nestor10/fishy-zio-http-slackbot:latest` - Always points to latest release

**Best practice:** Use version-specific tags in production for reproducibility.

## Deployment

### Podman Compose (Local/Development)

```bash
# Update podman-compose.yaml with the version tag
podman-compose up -d
```

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: fishy-zio-http-slackbot
spec:
  replicas: 1
  selector:
    matchLabels:
      app: fishy-zio-http-slackbot
  template:
    metadata:
      labels:
        app: fishy-zio-http-slackbot
    spec:
      containers:
      - name: slackbot
        image: quay.io/nestor10/fishy-zio-http-slackbot:0.1.0
        env:
        - name: APP_SLACK_APP_TOKEN
          valueFrom:
            secretKeyRef:
              name: slack-secrets
              key: app-token
        - name: APP_SLACK_BOT_TOKEN
          valueFrom:
            secretKeyRef:
              name: slack-secrets
              key: bot-token
        - name: APP_LLM_BASE_URL
          value: "http://ollama-service:11434"
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 8888
          name: metrics
        - containerPort: 8889
          name: health
```

### Docker Run (Quick Test)

```bash
podman run -d \
  --name fishy-slackbot \
  -e APP_SLACK_APP_TOKEN="xapp-..." \
  -e APP_SLACK_BOT_TOKEN="xoxb-..." \
  -e APP_LLM_BASE_URL="http://host.containers.internal:11434" \
  -p 8080:8080 \
  -p 8888:8888 \
  -p 8889:8889 \
  quay.io/nestor10/fishy-zio-http-slackbot:0.1.0
```

## Troubleshooting

### Build Failures

**Problem:** `sbt docker:publishLocal` fails with "Cannot run program 'docker'"

**Solution:** Ensure podman is installed and the `docker` alias is configured:
```bash
alias docker=podman
```

**Problem:** Multi-arch build fails in GitHub Actions

**Solution:** Check that QEMU and Buildx are properly set up in the workflow. The workflow includes these steps automatically.

### Release Failures

**Problem:** `sbt release` fails with "No tracking branch"

**Solution:** Ensure you're on the `main` branch with upstream tracking:
```bash
git checkout main
git branch --set-upstream-to=origin/main main
```

**Problem:** Tag already exists

**Solution:** Delete the tag locally and remotely, then retry:
```bash
git tag -d v0.1.0
git push origin :refs/tags/v0.1.0
sbt release
```

### Registry Issues

**Problem:** GitHub Actions fails to push to Quay.io

**Solution:** 
1. Verify secrets are set correctly: Settings → Secrets and variables → Actions
2. Check Quay.io repository permissions (robot account needs "Write" access)
3. Verify repository exists: `quay.io/nestor10/fishy-zio-http-slackbot`

## CI/CD Pipeline

### Workflows

1. **CI Workflow** (`.github/workflows/ci.yml`):
   - Triggers: Push to `main`, pull requests
   - Jobs: build, test, format (scalafmt, scalafix)
   - Caches: sbt dependencies, ivy2, coursier

2. **Publish Workflow** (`.github/workflows/publish.yml`):
   - Triggers: Push of `v*` tags
   - Jobs: Build multi-arch Docker images, push to Quay.io
   - Caching: GitHub Actions cache for Docker layers

### Monitoring Builds

- **CI Status:** [GitHub Actions](https://github.com/YOUR_ORG/fishy-zio-http-socket/actions)
- **Images:** [Quay.io Repository](https://quay.io/repository/nestor10/fishy-zio-http-slackbot)

## Next Steps

1. ✅ Set up Quay.io repository and robot account
2. ✅ Add GitHub secrets (`QUAY_USERNAME`, `QUAY_PASSWORD`)
3. 🔄 Test release process: `sbt "release release-version 0.1.0 next-version 0.2.0-SNAPSHOT"`
4. 🔄 Verify multi-arch images on Quay.io
5. 🔄 Update `README.md` with deployment instructions
6. 🔄 (Optional) Add branch protection rules for `main`

## References

- [sbt-release](https://github.com/sbt/sbt-release)
- [sbt-native-packager](https://www.scala-sbt.org/sbt-native-packager/)
- [Docker Buildx](https://docs.docker.com/buildx/working-with-buildx/)
- [Quay.io Documentation](https://docs.quay.io/)
