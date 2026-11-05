#!/usr/bin/env bash
# Inside the container, once (and again to upgrade): installs claude, the browser MCP and skills into the persistent HOME.
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

mkdir -p /workspace/out
claude --version
claude mcp list
ls ~/.claude/skills
