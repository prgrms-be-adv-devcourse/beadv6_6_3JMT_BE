#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST_ROOT="${1:-}"

fail() {
  echo "Kubernetes Secret key preflight failed: $1" >&2
  exit 1
}

[[ -n "${MANIFEST_ROOT}" ]] || fail "manifest root argument is required"
[[ -d "${MANIFEST_ROOT}" ]] || fail "manifest root does not exist: ${MANIFEST_ROOT}"
: "${NAMESPACE:?NAMESPACE is required}"
command -v kubectl >/dev/null 2>&1 || fail "kubectl is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

manifest_secret_pairs="$(
  while IFS= read -r manifest; do
    awk '
      /secretKeyRef:/ {
        in_secret_ref = 1
        secret_name = ""
        next
      }
      in_secret_ref && $1 == "name:" {
        secret_name = $2
        next
      }
      in_secret_ref && $1 == "key:" {
        if (secret_name != "") {
          print secret_name "|" $2
        }
        in_secret_ref = 0
      }
      in_secret_ref && /^[^[:space:]]/ {
        in_secret_ref = 0
      }
    ' "${manifest}"
  done < <(find "${MANIFEST_ROOT}" -type f \( -name '*.yaml' -o -name '*.yml' \) | sort)
)"

while IFS= read -r pair; do
  [[ -n "${pair}" ]] || continue
  secret_name="${pair%%|*}"
  key="${pair#*|}"
  secret_json=""

  if ! secret_json="$(kubectl get secret "${secret_name}" -n "${NAMESPACE}" -o json 2>/dev/null)"; then
    fail "${secret_name}.${key} (Secret not found)"
  fi

  if ! jq -e --arg key "${key}" '.data | has($key)' <<< "${secret_json}" >/dev/null 2>&1; then
    fail "${secret_name}.${key}"
  fi
done <<< "${manifest_secret_pairs}"

echo "Kubernetes Secret key preflight passed."
