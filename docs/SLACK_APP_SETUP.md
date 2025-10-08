# Slack App Setup Guide

## Quick Setup (5 minutes)

### 1. Create Slack App

📚 **[Follow Slack's official app creation guide](https://docs.slack.dev/#apps)**

**Quick option:** Use our included manifest for instant setup:

1. Go to [api.slack.com/apps](https://api.slack.com/apps)
2. Click **"Create New App"** → **"From an app manifest"**
3. Select your workspace
4. Copy/paste the contents of [`slack-app-manifest.yaml`](./slack-app-manifest.yaml)
5. Click **"Create"**

### 2. Get Your Tokens

After creating the app:

**App-Level Token** (for Socket Mode):
1. Go to **Settings** → **Basic Information** → **App-Level Tokens**
2. Click **"Generate Token and Scopes"**
3. Add `connections:write` scope
4. Copy token (starts with `xapp-`)

**Bot Token** (for posting messages):
1. Go to **Settings** → **Install App**
2. Click **"Install to Workspace"**
3. Copy **Bot User OAuth Token** (starts with `xoxb-`)

### 3. Enable Socket Mode

1. Go to **Settings** → **Socket Mode**
2. Toggle **"Enable Socket Mode"** to **ON**

### 4. Configure Environment

Save your tokens:

```bash
# In your .env file
APP_SLACK_APP_TOKEN="xapp-1-..."
APP_SLACK_BOT_TOKEN="xoxb-..."
```

✅ **Done!** Your bot is ready to run.

---

## Permissions Included

The manifest configures these scopes:
- `app_mentions:read` - Listen for @mentions
- `chat:write` - Post messages
- `channels:history`, `groups:history`, `im:history`, `mpim:history` - Read thread context
- `reactions:write` - React to messages (optional)

---

📖 **More details**: [Slack API Documentation](https://api.slack.com/start)
