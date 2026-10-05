#!/usr/bin/env bash
set -euo pipefail
umask 077
commit=${1:-};archive_sha=${2:-}
[[ "$commit" =~ ^[0-9a-f]{40}$ && "$archive_sha" =~ ^[0-9a-f]{64}$ ]] || exit 64
root=/apps/kaifan
cd "$root"
# Coordinate deployment with the existing consistent daily backup.
exec 9>"$root/backups/.backup.lock"
flock -w 300 9
state="$root/config/github-deployment.json"
previous=$(readlink -f "$root/current")
[[ "$previous" == "$root/releases/"* && -f "$previous/backend.jar" ]] || exit 65
stamp=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d "$root/releases/.github-${commit:0:12}-XXXXXX")
trap 'rm -rf "$work"' EXIT
python3 -c '
import sys
size=0
with open(sys.argv[1],"wb") as out:
 while True:
  block=sys.stdin.buffer.read(1024*1024)
  if not block:break
  size+=len(block)
  if size>128*1024*1024:raise SystemExit("Release stream too large")
  out.write(block)
' "$work/upload.tar.gz"
echo "$archive_sha  $work/upload.tar.gz" | sha256sum -c - >/dev/null
python3 /usr/local/libexec/kaifan-validate-release.py "$work/upload.tar.gz" "$work/release" "$commit"
if [[ -f "$state" ]] && python3 - "$state" "$commit" "$previous" <<'REPLAY'
import json,sys
s=json.load(open(sys.argv[1]));raise SystemExit(0 if s.get('commit')==sys.argv[2] and s.get('releasePath')==sys.argv[3] else 1)
REPLAY
then
 echo "Commit $commit is already deployed; no duplicate publication."
 exit 0
fi
python3 /usr/local/libexec/kaifan-cache-libraries.py restore "$work/release"
dc=(docker compose --env-file "$root/config/private.env" -f "$root/compose.yaml")
mkdir -p "$root/backups/github"
"${dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces kaifan' | gzip > "$root/backups/github/$stamp-${commit:0:12}.sql.gz"
echo 'Database snapshot saved; checking migrations.'
python3 - "$root" "$work/release/migrations" <<'MIGRATIONS'
from pathlib import Path
import json,hashlib,re,subprocess,sys,os
root=Path(sys.argv[1]);folder=Path(sys.argv[2]);state=root/'config/github-migrations.json'
known=json.loads(state.read_text())
for file in sorted(folder.glob('*.sql')):
 if not re.fullmatch(r'[0-9]{8}-[a-z0-9-]+\.sql',file.name):raise SystemExit('Invalid migration name')
 digest=hashlib.sha256(file.read_bytes()).hexdigest()
 if file.name in known:
  if known[file.name]!=digest:raise SystemExit('Applied migration changed: '+file.name+'; add a new migration instead.')
  continue
 print('Applying migration:',file.name,flush=True)
 cmd=['docker','compose','--env-file',str(root/'config/private.env'),'-f',str(root/'compose.yaml'),'exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --default-character-set=utf8mb4 "$MYSQL_DATABASE"']
 subprocess.run(cmd,input=file.read_bytes(),check=True)
 known[file.name]=digest;temp=state.with_suffix('.next');temp.write_text(json.dumps(known,indent=2));os.chmod(temp,0o600);os.replace(temp,state)
MIGRATIONS
# Retain old hashed frontend assets for clients with an already-open page.
python3 - "$previous/web" "$work/release/web" <<'ASSETS'
from pathlib import Path
import sys,shutil
old,new=map(Path,sys.argv[1:])
for relative in ['assets','order/assets']:
 folder=old/relative
 if not folder.exists():continue
 for file in folder.rglob('*'):
  if file.is_file():
   target=new/relative/file.relative_to(folder)
   if not target.exists():target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,target)
ASSETS
release="$root/releases/$stamp-gh-${commit:0:12}"
mv "$work/release" "$release"
ln -s "$release" "$root/current.github-next"
mv -Tf "$root/current.github-next" "$root/current"
rollback() {
 echo 'Release did not become healthy; restoring previous code.' >&2
 ln -s "$previous" "$root/current.github-next"
 mv -Tf "$root/current.github-next" "$root/current"
 "${dc[@]}" up -d --no-deps --force-recreate backend web >/dev/null
}
if ! "${dc[@]}" up -d --no-deps --force-recreate backend web >/dev/null; then rollback; exit 1; fi
healthy=false
for attempt in $(seq 1 36); do
 if docker exec kaifan-backend-1 sh -c 'curl -fsS http://127.0.0.1:8080/api/app/dish/category/list' 2>/dev/null | python3 -c 'import json,sys;raise SystemExit(0 if json.load(sys.stdin).get("code")==200 else 1)' 2>/dev/null; then
  if curl -fsS http://127.0.0.1:18080/build-version.json | python3 -c 'import json,sys;raise SystemExit(0 if json.load(sys.stdin).get("commit")==sys.argv[1] else 1)' "$commit" 2>/dev/null && curl -fsS http://127.0.0.1:18080/order/build-version.json | python3 -c 'import json,sys;raise SystemExit(0 if json.load(sys.stdin).get("commit")==sys.argv[1] else 1)' "$commit" 2>/dev/null; then healthy=true;break;fi
 fi
 sleep 5
done
if [[ "$healthy" != true ]]; then rollback; exit 1; fi
python3 - "$state" "$commit" "$release" "$previous" <<'STATE'
import json,sys,datetime,os
p=sys.argv[1];temp=p+'.next';data={'commit':sys.argv[2],'releasePath':sys.argv[3],'previousReleasePath':sys.argv[4],'deployedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
with open(temp,'w') as out:json.dump(data,out,indent=2)
os.chmod(temp,0o600);os.replace(temp,p)
STATE
echo "Deployed $commit; API and both frontend versions are healthy."
