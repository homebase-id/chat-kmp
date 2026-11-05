#!/usr/bin/env bash
# Run once as root on the box: sudo bash setup-root.sh
set -euo pipefail
cd "$(dirname "$0")"

U=marketing
ADMIN=${SUDO_USER:-biswa}

if ! id "$U" >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash --comment "Homebase marketing agent" "$U"
fi
passwd -l "$U" >/dev/null
gpasswd -d "$U" sudo >/dev/null 2>&1 || true
grep -q "^$U:" /etc/subuid || usermod --add-subuids 231072-296607 --add-subgids 231072-296607 "$U"
loginctl enable-linger "$U"
chmod 700 "/home/$U"
# /home/biswa was 755: the agent user could read it
chmod 750 "/home/$ADMIN"

# admin logs in as the agent user with the same keys (sshd is key-only)
install -d -m 700 -o "$U" -g "$U" "/home/$U/.ssh"
install -m 600 -o "$U" -g "$U" "/home/$ADMIN/.ssh/authorized_keys" "/home/$U/.ssh/authorized_keys"

install -d /etc/nftables.d
install -m 644 marketing-egress.nft /etc/nftables.d/marketing-egress.nft
install -m 644 marketing-egress.service /etc/systemd/system/marketing-egress.service
systemctl daemon-reload
systemctl enable --now marketing-egress.service
systemctl restart marketing-egress.service

nft list table inet marketing_egress >/dev/null && echo "ok: user $U, egress rules loaded"
