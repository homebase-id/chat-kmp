#!/usr/bin/env bash
# As the brain user, once (and again to upgrade): podman exec -u brain -e HOME=/home/agent -w /home/agent marketing-agent bash bootstrap.sh
# Installs claude, the browser MCP and skills into the persistent HOME, and clones the workspace repo (needs the deploy key in ~/.ssh).
set -euo pipefail

command -v claude >/dev/null || curl -fsSL https://claude.ai/install.sh | bash

npm install -g "@playwright/mcp@$(npm view @playwright/mcp version)"
claude mcp remove --scope user playwright >/dev/null 2>&1 || true
claude mcp add --scope user playwright -- playwright-mcp --headless --isolated --browser chromium

# Skills only: the plugin also declares a dozen OAuth MCP servers that would be dialled on every run.
KWP=~/.claude/skill-sources/knowledge-work-plugins
if [ -d "$KWP/.git" ]; then git -C "$KWP" pull -q; else git clone -q --depth 1 https://github.com/anthropics/knowledge-work-plugins "$KWP"; fi
mkdir -p ~/.claude/skills
cp -r "$KWP"/marketing/skills/. ~/.claude/skills/

[ -d /workspace/.git ] || git clone -q git@github.com:homebase-id/marketing-assets.git /workspace
(cd /workspace/render && npm ci --silent)
mkdir -p /workspace/out
claude --version
claude mcp list
ls ~/.claude/skills
