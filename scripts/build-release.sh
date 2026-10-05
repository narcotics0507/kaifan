#!/usr/bin/env bash
set -euo pipefail
commit=${1:-$(git rev-parse HEAD)}
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || exit 2
mkdir -p release/web/order release/migrations
cp diancan-admin/target/diancan-admin-1.0.0.jar release/backend.jar
export VITE_SERVICE_BASE_URL=/api
export VITE_XUNHUPAY_RETURN_URL="${PUBLIC_ORIGIN:-http://localhost:18080}/service/checkout"
(cd diancan-admin-web && pnpm exec vite build --mode prod --outDir ../release/web --emptyOutDir)
[[ -e diancan-customer-web/node_modules ]] || ln -s ../diancan-admin-web/node_modules diancan-customer-web/node_modules
(cd diancan-customer-web && ../diancan-admin-web/node_modules/.bin/vite build --outDir ../release/web/order --emptyOutDir)
mkdir -p release/migrations release/licenses
cp LICENSE release/licenses/LICENSE
cp THIRD_PARTY_NOTICES.md release/licenses/THIRD_PARTY_NOTICES.md
cp diancan-admin-web/LICENSE release/licenses/merchant-MIT.txt
cp db/upgrade/*.sql release/migrations/
python3 - "$commit" <<'PY'
from pathlib import Path
import sys,hashlib,json,datetime
root=Path('release');commit=sys.argv[1]
metadata={'commit':commit,'builtAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'repository':'narcotics0507/kaifan'}
(root/'release.json').write_text(json.dumps(metadata))
for p in [root/'web/build-version.json',root/'web/order/build-version.json']:p.write_text(json.dumps(metadata))
files=sorted(p for p in root.rglob('*') if p.is_file() and p.name!='SHA256SUMS')
(root/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(root).as_posix()+'\n' for p in files))
PY
# Explicit members; private working files never enter the release archive.
tar -C release -czf kaifan-release.tar.gz backend.jar web migrations licenses release.json SHA256SUMS
