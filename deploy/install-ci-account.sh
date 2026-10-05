#!/usr/bin/env bash
# Run as root on an existing /apps/kaifan deployment; never overwrites business configuration.
set -euo pipefail
umask 077
source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
public_key=${1:?Pass the dedicated deployment public key file}
[[ $(id -u) -eq 0 && -f /apps/kaifan/compose.yaml && -f "$public_key" ]] || exit 2
[[ $(wc -l < "$public_key") -eq 1 ]] || exit 2
grep -q '^ssh-ed25519 ' "$public_key" || exit 2
id kaifan-deploy >/dev/null 2>&1 || useradd --system --create-home --home-dir /home/kaifan-deploy --shell /bin/bash kaifan-deploy
# Impossible password; only the separately configured restricted public key is usable.
usermod -p '*' kaifan-deploy
install -d -m 755 /usr/local/libexec
install -m 755 "$source_dir/ssh-entry.sh" /usr/local/sbin/kaifan-github-entry
install -m 700 "$source_dir/apply-release.sh" /usr/local/sbin/kaifan-github-deploy
install -m 755 "$source_dir/validate-release.py" /usr/local/libexec/kaifan-validate-release.py
install -m 755 "$source_dir/cache-libraries.py" /usr/local/libexec/kaifan-cache-libraries.py
printf '#!/usr/bin/env bash\nexec python3 /usr/local/libexec/kaifan-cache-libraries.py\n' > /usr/local/sbin/kaifan-github-cache
chmod 700 /usr/local/sbin/kaifan-github-cache
python3 /usr/local/libexec/kaifan-cache-libraries.py seed "$(readlink -f /apps/kaifan/current)/backend.jar"
install -d -m 700 -o kaifan-deploy -g kaifan-deploy /home/kaifan-deploy/.ssh
printf 'restrict,command="/usr/local/sbin/kaifan-github-entry" %s\n' "$(cat "$public_key")" > /home/kaifan-deploy/.ssh/authorized_keys
chown kaifan-deploy:kaifan-deploy /home/kaifan-deploy/.ssh/authorized_keys
chmod 600 /home/kaifan-deploy/.ssh/authorized_keys
cat > /etc/sudoers.d/kaifan-github <<'SUDOERS'
Defaults:kaifan-deploy !setenv
kaifan-deploy ALL=(root) NOPASSWD: /usr/local/sbin/kaifan-github-deploy *, /usr/local/sbin/kaifan-github-cache
SUDOERS
chmod 440 /etc/sudoers.d/kaifan-github
visudo -cf /etc/sudoers.d/kaifan-github >/dev/null
# The baseline is valid only for an already-upgraded server. Verify actual schema first.
if [[ ! -f /apps/kaifan/config/github-migrations.json ]]; then
 result=$(docker compose --env-file /apps/kaifan/config/private.env -f /apps/kaifan/compose.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --batch --skip-column-names "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name=\"dining_table\" AND column_name=\"current_session_code\") OR (table_name=\"order\" AND column_name=\"table_session_code\") OR (table_name=\"home_banner\" AND column_name=\"scene\")); SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (\"h5_submission\",\"kitchen_sequence\");"')
 [[ "$result" == $'3\n2' ]] || { echo 'Server schema differs; baseline not recorded.' >&2; exit 1; }
 install -m 600 "$source_dir/baseline-migrations.json" /apps/kaifan/config/github-migrations.json
fi
echo 'Restricted CI publisher installed. Existing application credentials and data retained.'
