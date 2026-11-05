#!/usr/bin/env bash
# As the marketing user, from the unpacked setup dir (contains chat-agent/ from the distTar).
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p ~/data/marketing ~/data/home ~/home ~/build ~/.config/containers/systemd
chmod 700 ~/data ~/data/marketing ~/data/home ~/home
[ -f ~/secrets.env ] || install -m 600 /dev/null ~/secrets.env
[ -f ~/data/marketing/agent.conf ] || install -m 600 agent.conf ~/data/marketing/agent.conf

mkdir -p ~/workspace  # bootstrap.sh clones homebase-id/marketing-assets into it

rm -rf ~/build/chat-agent && cp -r chat-agent Containerfile ~/build/
podman build -t localhost/marketing-agent:latest ~/build
# the operator brain (uid 2000 "brain") owns its home and the workspace; the agent reads them through the brain group
podman run --rm --userns=keep-id --user 0 -v ~/home:/home/agent -v ~/workspace:/workspace --entrypoint sh localhost/marketing-agent:latest \
    -c "chown -R 2000:2000 /home/agent /workspace && chmod 750 /home/agent"
install -m 644 marketing-agent.container ~/.config/containers/systemd/
install -m 755 bootstrap.sh ~/home/bootstrap.sh
systemctl --user daemon-reload
echo "ok: image built, quadlet installed (not started)"
