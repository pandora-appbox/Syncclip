#!/usr/bin/env bash
# Existing Debian/Ubuntu VPS with nginx, HTTPS and passwordless sudo.
set -euo pipefail
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
host=${1:?Usage: deploy-vps.sh SSH_HOST HTTPS_DOMAIN NGINX_SITE_PATH}
domain=${2:?HTTPS domain required}
site=${3:?Existing nginx site path required}
[[ "$host" =~ ^[a-zA-Z0-9_.@-]+$ && "$domain" =~ ^[a-zA-Z0-9.-]+$ && "$site" =~ ^/etc/nginx/sites-available/[a-zA-Z0-9_.-]+$ ]] || { echo 'Invalid deployment arguments';exit 1; }
test -f "$task_root/private/relay.env"
test -f "$task_root/artifacts/syncclip-server"
remote=$(ssh -o BatchMode=yes "$host" 'mktemp -d /tmp/syncclip-deploy.XXXXXXXX')
[[ "$remote" =~ ^/tmp/syncclip-deploy.[a-zA-Z0-9]+$ ]]
trap 'ssh -o BatchMode=yes "$host" "rm -rf -- $remote" >/dev/null 2>&1 || true' EXIT
scp -q "$task_root/artifacts/syncclip-server" "$task_root/private/relay.env" "$task_root/deploy/syncclip.service" "$task_root/deploy/nginx-syncclip.conf" "$host:$remote/"
ssh -o BatchMode=yes "$host" "sudo -n bash -s -- '$remote' '$domain' '$site'" <<'REMOTE'
set -Eeuo pipefail
bundle=$1; domain=$2; site=$3
backup=$(mktemp -d /var/tmp/syncclip-rollback.XXXXXXXX)
chmod 700 "$backup"
targets=(/opt/syncclip/syncclip-server /etc/syncclip/relay.env /etc/systemd/system/syncclip.service /etc/nginx/snippets/syncclip.conf "$site")
for i in "${!targets[@]}"; do
    if [ -f "${targets[$i]}" ]; then cp -p -- "${targets[$i]}" "$backup/$i"; fi
done
was_active=0
if systemctl is-active --quiet syncclip.service; then was_active=1; fi
service_touched=0
nginx_touched=0
rollback() {
    result=$?
    trap - ERR
    set +e
    restore_failed=0
    for i in "${!targets[@]}"; do
        if [ -f "$backup/$i" ]; then
            # Rename a sibling inode: overwriting an executing binary fails with ETXTBSY.
            if ! cp -p -- "$backup/$i" "${targets[$i]}.rollback" ||
               ! mv -f -- "${targets[$i]}.rollback" "${targets[$i]}"; then restore_failed=1; fi
        elif ! rm -f -- "${targets[$i]}"; then restore_failed=1; fi
    done
    if [ "$restore_failed" = 1 ]; then
        echo "Deployment failed; file restoration incomplete. Recovery copies: $backup" >&2
        exit "$result"
    fi
    if [ "$service_touched" = 1 ]; then
        systemctl daemon-reload
        if [ "$was_active" = 1 ]; then systemctl restart syncclip.service;
        else systemctl stop syncclip.service; fi
    fi
    if [ "$nginx_touched" = 1 ]; then nginx -t && systemctl reload nginx; fi
    echo "Deployment failed; restored previous files. Recovery copies: $backup" >&2
    exit "$result"
}
trap rollback ERR
install -d -m 755 /opt/syncclip
install -d -m 700 /etc/syncclip
if [ -f /opt/syncclip/syncclip-server ]; then cp -p /opt/syncclip/syncclip-server /opt/syncclip/syncclip-server.previous; fi
install -m 755 "$bundle/syncclip-server" /opt/syncclip/syncclip-server.new
mv /opt/syncclip/syncclip-server.new /opt/syncclip/syncclip-server
install -m 600 "$bundle/relay.env" /etc/syncclip/relay.env
install -m 644 "$bundle/syncclip.service" /etc/systemd/system/syncclip.service
install -m 644 "$bundle/nginx-syncclip.conf" /etc/nginx/snippets/syncclip.conf
python3 - "$site" "$domain" <<'PY'
from pathlib import Path
import sys
path=Path(sys.argv[1]);domain=sys.argv[2];text=path.read_text()
include="include /etc/nginx/snippets/syncclip.conf;"
anchor="server_name "+domain+";"
if include not in text:
    if text.count(anchor)!=1:raise SystemExit("Cannot identify a unique HTTPS virtual host")
    path.write_text(text.replace(anchor,anchor+"\n    "+include,1))
PY
nginx -t
service_touched=1
systemctl daemon-reload
systemctl enable --now syncclip.service
systemctl restart syncclip.service
for attempt in 1 2 3 4 5; do if curl -fsS http://127.0.0.1:8787/healthz >/dev/null;then break;fi;sleep 1;done
curl -fsS http://127.0.0.1:8787/healthz
nginx_touched=1
systemctl reload nginx
systemctl is-active syncclip.service
trap - ERR
echo "Recovery copies: $backup"
REMOTE
curl --fail --silent --show-error "https://$domain/syncclip/healthz"
echo
echo "Syncclip is available at https://$domain/syncclip"
