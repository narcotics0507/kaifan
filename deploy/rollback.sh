#!/usr/bin/env bash
set -euo pipefail
root=/apps/kaifan
release=${1:?Pass a saved release directory name}
[[ "$release" =~ ^[0-9]{8}T[0-9]{6}Z(-gh-[0-9a-f]{12})?$ ]] || exit 2
[[ -f "$root/releases/$release/backend.jar" && -f "$root/releases/$release/web/order/index.html" ]] || exit 2
exec 9>"$root/backups/.backup.lock";flock -w 300 9
ln -s "$root/releases/$release" "$root/current.github-next"
mv -Tf "$root/current.github-next" "$root/current"
docker compose --env-file "$root/config/private.env" -f "$root/compose.yaml" up -d --force-recreate backend web
