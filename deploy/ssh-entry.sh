#!/usr/bin/env bash
set -euo pipefail
if [[ "${SSH_ORIGINAL_COMMAND:-}" == dependency-cache ]]; then
  exec sudo -n /usr/local/sbin/kaifan-github-cache
fi
if [[ "${SSH_ORIGINAL_COMMAND:-}" =~ ^release\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo -n /usr/local/sbin/kaifan-github-deploy "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi
echo 'This account accepts only a validated Kaifan release stream.' >&2
exit 64
