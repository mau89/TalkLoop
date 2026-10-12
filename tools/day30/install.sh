#!/usr/bin/env bash
set -euo pipefail

# Run on the VPS as root. Does not stop VPN services or change firewall rules.
[[ $(id -u) == 0 ]] || { echo 'Run as root'; exit 1; }
task_ip=${1:?Usage: bash install.sh PUBLIC_IPV4}
python3 - "$task_ip" <<'PY'
import ipaddress, sys
assert ipaddress.ip_address(sys.argv[1]).version == 4
PY
task_source=$(cd -- "$(dirname -- "$0")" && pwd)
if ! systemctl is-active --quiet talkloop-private-ai; then
    python3 - <<'PY'
import socket
for port in (80, 18443):
    with socket.socket() as sock:
        try:
            sock.bind(('0.0.0.0', port))
        except OSError:
            raise SystemExit(f'Port {port} is occupied. Installation stopped; existing service was not changed.')
PY
fi
systemctl is-active --quiet ollama || { echo 'Start Ollama first'; exit 1; }
ollama list | grep -q 'qwen2.5:0.5b' || { echo 'Download qwen2.5:0.5b first'; exit 1; }

apt-get update
apt-get install -y python3-venv
if [[ ! -x /opt/talkloop-certbot/bin/certbot ]]; then
    python3 -m venv /opt/talkloop-certbot
    /opt/talkloop-certbot/bin/pip install 'certbot>=5.4,<6'
fi
# IP certificates require public TCP port 80 for issuance and renewal.
/opt/talkloop-certbot/bin/certbot certonly --non-interactive --agree-tos \
    --register-unsafely-without-email --preferred-profile shortlived \
    --standalone --cert-name talkloop-ip --ip-address "$task_ip" --keep-until-expiring

id talkloop-ai >/dev/null 2>&1 || useradd --system --user-group --no-create-home --shell /usr/sbin/nologin talkloop-ai
install -d -o root -g root -m 755 /opt/talkloop-private-ai
install -m 644 "$task_source/gateway.py" /opt/talkloop-private-ai/gateway.py
install -d -o root -g talkloop-ai -m 750 /etc/talkloop-private-ai
if [[ ! -f /etc/talkloop-private-ai/token ]]; then
    python3 - <<'PY'
import os, secrets
path = '/etc/talkloop-private-ai/token'
fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
with os.fdopen(fd, 'w') as file:
    file.write(secrets.token_urlsafe(32) + '\n')
PY
fi
chown root:talkloop-ai /etc/talkloop-private-ai/token
chmod 640 /etc/talkloop-private-ai/token

install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/talkloop-private-ai <<'HOOK'
#!/usr/bin/env bash
set -euo pipefail
[[ ${RENEWED_LINEAGE:-} == /etc/letsencrypt/live/talkloop-ip ]] || exit 0
install -o root -g talkloop-ai -m 640 "$RENEWED_LINEAGE/fullchain.pem" /etc/talkloop-private-ai/fullchain.pem
install -o root -g talkloop-ai -m 640 "$RENEWED_LINEAGE/privkey.pem" /etc/talkloop-private-ai/privkey.pem
if systemctl is-active --quiet talkloop-private-ai; then
    systemctl reload talkloop-private-ai
fi
HOOK
chmod 750 /etc/letsencrypt/renewal-hooks/deploy/talkloop-private-ai
RENEWED_LINEAGE=/etc/letsencrypt/live/talkloop-ip /etc/letsencrypt/renewal-hooks/deploy/talkloop-private-ai

cat > /etc/systemd/system/talkloop-private-ai.service <<'SERVICE'
[Unit]
Description=TalkLoop private Ollama HTTPS API
After=network-online.target ollama.service
Wants=network-online.target ollama.service

[Service]
Type=simple
User=talkloop-ai
Group=talkloop-ai
Environment=TOKEN_FILE=/etc/talkloop-private-ai/token
Environment=TLS_CERT=/etc/talkloop-private-ai/fullchain.pem
Environment=TLS_KEY=/etc/talkloop-private-ai/privkey.pem
Environment=PYTHONDONTWRITEBYTECODE=1
ExecStart=/usr/bin/python3 /opt/talkloop-private-ai/gateway.py
ExecReload=/bin/kill -HUP $MAINPID
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
MemoryMax=96M
TasksMax=24
CPUQuota=10%

[Install]
WantedBy=multi-user.target
SERVICE
cat > /etc/systemd/system/talkloop-cert-renew.service <<'SERVICE'
[Unit]
Description=Renew TalkLoop IP HTTPS certificate
[Service]
Type=oneshot
ExecStart=/opt/talkloop-certbot/bin/certbot renew --cert-name talkloop-ip --quiet
SERVICE
cat > /etc/systemd/system/talkloop-cert-renew.timer <<'TIMER'
[Unit]
Description=Check TalkLoop IP certificate every six hours
[Timer]
OnCalendar=*-*-* 00,06,12,18:00:00
RandomizedDelaySec=900
Persistent=true
[Install]
WantedBy=timers.target
TIMER
systemctl daemon-reload
systemctl enable --now talkloop-private-ai talkloop-cert-renew.timer
systemctl restart talkloop-private-ai
systemctl is-active talkloop-private-ai
echo "Ready: https://$task_ip:18443"
echo 'Show your private service key locally: cat /etc/talkloop-private-ai/token'
echo 'Do not share the key in screenshots or commit it. Ollama stays on 127.0.0.1:11434.'
