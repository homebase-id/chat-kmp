#!/usr/bin/env bash
# As the marketing user, from the unpacked setup dir (contains chat-agent/ from the distTar).
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p ~/data/marketing ~/home ~/build ~/.config/containers/systemd
chmod 700 ~/data ~/data/marketing ~/home
[ -f ~/secrets.env ] || install -m 600 /dev/null ~/secrets.env
[ -f ~/data/marketing/agent.conf ] || install -m 600 agent.conf ~/data/marketing/agent.conf

if [ ! -d ~/workspace/.git ]; then
    cp -r workspace ~/workspace
    git -C ~/workspace init -q -b main
    git -C ~/workspace -c user.name="Homebase marketing agent" -c user.email=marketing@localhost add -A
    git -C ~/workspace -c user.name="Homebase marketing agent" -c user.email=marketing@localhost commit -qm "Workspace skeleton"
fi

rm -rf ~/build/chat-agent && cp -r chat-agent Containerfile ~/build/
podman build -t localhost/marketing-agent:latest ~/build
install -m 644 marketing-agent.container ~/.config/containers/systemd/
install -m 755 bootstrap.sh ~/home/bootstrap.sh
systemctl --user daemon-reload
echo "ok: image built, quadlet installed (not started)"
