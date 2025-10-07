# Release Checklist

## Status: Ready for First Release ✅

All prerequisites complete. Follow these steps to publish `v0.1.0`.

---

## Step 1: Set Up Quay.io (15 min)

- [ ] **Create Quay.io account** (if needed): https://quay.io/signin/
- [ ] **Create repository**:
  - Go to: https://quay.io/new/
  - Name: `fishy-zio-http-slackbot`
  - Namespace: `nestor10`
  - Visibility: Public (or Private)
  - Click "Create Public Repository"
- [ ] **Create robot account**:
  - Go to: https://quay.io/organization/nestor10?tab=robots
  - Click "Create Robot Account"
  - Name: `github_actions` (or similar)
  - Grant "Write" permission to `fishy-zio-http-slackbot`
  - Save the credentials (username + token)

**Expected Result:**
- Repository URL: `quay.io/nestor10/fishy-zio-http-slackbot`
- Robot username: `nestor10+github_actions`
- Robot token: (saved securely)

---

## Step 2: Configure GitHub Secrets (5 min)

- [ ] **Navigate to GitHub repository settings**:
  - URL: https://github.com/YOUR_ORG/fishy-zio-http-socket/settings/secrets/actions
- [ ] **Add QUAY_USERNAME secret**:
  - Click "New repository secret"
  - Name: `QUAY_USERNAME`
  - Value: `nestor10+github_actions` (your robot account username)
  - Click "Add secret"
- [ ] **Add QUAY_PASSWORD secret**:
  - Click "New repository secret"
  - Name: `QUAY_PASSWORD`
  - Value: (paste robot account token)
  - Click "Add secret"

**Expected Result:**
- Two secrets visible in Actions secrets list
- Values are masked (••••••••)

---

## Step 3: Test Release Locally (10 min)

Before publishing, test the release process locally:

```bash
# Ensure you're on main with latest changes
git checkout main
git pull

# Test release with defaults (non-interactive)
sbt "release release-version 0.1.0 next-version 0.2.0-SNAPSHOT"
```

**Expected Output:**
```
[info] Setting version to 0.1.0
[info] Running tests...
[success] Total time: X s
[info] Committing release version
[info] Tagging release v0.1.0
[info] Publishing Docker image...
[success] Built image quay.io/nestor10/fishy-zio-http-slackbot:0.1.0
[info] Setting next version to 0.2.0-SNAPSHOT
[info] Committing next version
[info] Pushing to origin...
To https://github.com/YOUR_ORG/fishy-zio-http-socket.git
 * [new tag]         v0.1.0 -> v0.1.0
```

**What happens:**
- ✅ Tests run
- ✅ Version updated to `0.1.0` in `version.sbt`
- ✅ Commit created: "Setting version to 0.1.0"
- ✅ Git tag created: `v0.1.0`
- ✅ Docker image built locally (single-arch verification)
- ✅ Version updated to `0.2.0-SNAPSHOT`
- ✅ Commit created: "Setting version to 0.2.0-SNAPSHOT"
- ✅ All commits and tag pushed to GitHub

---

## Step 4: Monitor GitHub Actions (5 min)

After the tag is pushed, GitHub Actions will automatically trigger:

- [ ] **Go to Actions tab**: https://github.com/YOUR_ORG/fishy-zio-http-socket/actions
- [ ] **Find "Publish Docker Image" workflow**
- [ ] **Click on the running workflow** (triggered by `v0.1.0` tag)
- [ ] **Monitor the steps**:
  - ✅ Checkout code
  - ✅ Setup Java 23
  - ✅ Setup sbt
  - ✅ Set up QEMU (for ARM64 emulation)
  - ✅ Set up Docker Buildx (for multi-arch)
  - ✅ Login to Quay.io
  - ✅ Build application package (`sbt Docker/stage`)
  - ✅ Build and push multi-arch image (AMD64 + ARM64)

**Expected Duration:** 5-10 minutes

**Expected Result:**
- ✅ Workflow completes successfully (green checkmark)
- ✅ Multi-arch manifest pushed to Quay.io

---

## Step 5: Verify Docker Images (5 min)

- [ ] **Check Quay.io repository**:
  - Go to: https://quay.io/repository/nestor10/fishy-zio-http-slackbot?tab=tags
  - Verify two tags exist:
    - `0.1.0` (immutable version)
    - `latest` (points to 0.1.0)
- [ ] **Check multi-arch manifest**:
  - Click on `0.1.0` tag
  - Verify "Manifest List" shows:
    - `linux/amd64`
    - `linux/arm64`

**Expected Result:**
- ✅ Two tags visible in Quay.io
- ✅ Each tag has multi-arch manifest
- ✅ Image size ~100-200 MB per architecture

---

## Step 6: Test Docker Image (10 min)

Pull and test the published image:

```bash
# Pull the image (will auto-select correct architecture)
podman pull quay.io/nestor10/fishy-zio-http-slackbot:0.1.0

# Verify architecture
podman inspect quay.io/nestor10/fishy-zio-http-slackbot:0.1.0 | grep -i architecture

# Test run (requires Slack tokens)
podman run --rm \
  -e APP_SLACK_APP_TOKEN="xapp-..." \
  -e APP_SLACK_BOT_TOKEN="xoxb-..." \
  -e APP_LLM_BASE_URL="http://host.containers.internal:11434" \
  quay.io/nestor10/fishy-zio-http-slackbot:0.1.0
```

**Expected Output:**
```
[INFO] Application starting...
[INFO] Connecting to Slack via Socket Mode...
[INFO] WebSocket connected successfully
[INFO] Bot is online and listening for messages
```

**Verification:**
- ✅ Image pulls successfully
- ✅ Architecture matches your system (arm64 or amd64)
- ✅ Application starts without errors
- ✅ Connects to Slack successfully
- ✅ Responds to messages in Slack

---

## Step 7: Update Documentation (10 min)

- [ ] **Update README.md** with deployment instructions:
  - Add "Quick Start" section with Docker run command
  - Add "Configuration" section with environment variables
  - Add link to `docs/RELEASE.md`
- [ ] **Commit and push documentation**:
  ```bash
  git add README.md
  git commit -m "docs: Add deployment and configuration instructions"
  git push
  ```

---

## Step 8: (Optional) Branch Protection (10 min)

Protect the `main` branch to enforce CI checks:

- [ ] **Go to branch settings**:
  - URL: https://github.com/YOUR_ORG/fishy-zio-http-socket/settings/branches
- [ ] **Add rule for `main`**:
  - Branch name pattern: `main`
  - Enable: "Require status checks to pass before merging"
  - Select required checks:
    - `build`
    - `test`
    - `format`
  - Enable: "Require branches to be up to date before merging"
  - Enable: "Require linear history" (optional, keeps clean history)
- [ ] **Save changes**

**Result:**
- ✅ PRs must pass all CI checks before merge
- ✅ Clean, linear Git history
- ✅ No direct pushes to main (force push protection)

---

## Success Criteria

- ✅ `v0.1.0` tag exists in GitHub
- ✅ Docker images published to Quay.io (AMD64 + ARM64)
- ✅ Image pulls and runs successfully
- ✅ Bot connects to Slack and responds to messages
- ✅ Documentation updated with deployment instructions
- ✅ (Optional) Branch protection enabled

---

## Rollback Plan

If something goes wrong:

1. **Delete the tag locally and remotely**:
   ```bash
   git tag -d v0.1.0
   git push origin :refs/tags/v0.1.0
   ```

2. **Delete the images from Quay.io**:
   - Go to tag settings and delete

3. **Fix the issue** (code, workflow, secrets)

4. **Retry the release**:
   ```bash
   sbt "release release-version 0.1.0 next-version 0.2.0-SNAPSHOT"
   ```

---

## Next Release

For subsequent releases:

```bash
# Interactive (asks for versions)
sbt release

# Or automated (bumps last part)
sbt release with-defaults
```

The workflow is fully automated after the first release.
