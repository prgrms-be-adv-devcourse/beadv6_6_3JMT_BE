#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="${ROOT_DIR}/scripts/validate-live-k8s-secret-keys.sh"
fixture_dir="$(mktemp -d)"
output_file="${fixture_dir}/checker.out"

fail() {
  echo "Live Kubernetes Secret key validator test failed: $1" >&2
  exit 1
}

cleanup() {
  rm -rf "${fixture_dir}"
}
trap cleanup EXIT

mkdir -p "${fixture_dir}/manifests" "${fixture_dir}/bin"
printf '%s\n' \
  'apiVersion: v1' \
  'kind: Pod' \
  'metadata:' \
  '  name: fixture' \
  'spec:' \
  '  containers:' \
  '    - name: fixture' \
  '      image: busybox:1.37.0' \
  '      env:' \
  '        - name: PRESENT' \
  '          valueFrom:' \
  '            secretKeyRef:' \
  '              name: runtime-secret' \
  '              key: PRESENT_KEY' \
  > "${fixture_dir}/manifests/present.yaml"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'if [[ "$1" != "get" || "$2" != "secret" ]]; then' \
  '  exit 1' \
  'fi' \
  'printf %s '\''{"data":{"PRESENT_KEY":"redacted"}}'\''' \
  > "${fixture_dir}/bin/kubectl"
chmod +x "${fixture_dir}/bin/kubectl"

run_checker() {
  PATH="${fixture_dir}/bin:${PATH}" \
    NAMESPACE=prompthub \
    bash "${CHECKER}" "${fixture_dir}/manifests" >"${output_file}" 2>&1
}

if run_checker; then
  :
else
  fail "present key expected success: $(<"${output_file}")"
fi

printf '%s\n' \
  'apiVersion: v1' \
  'kind: Pod' \
  'metadata:' \
  '  name: missing-fixture' \
  'spec:' \
  '  containers:' \
  '    - name: fixture' \
  '      image: busybox:1.37.0' \
  '      env:' \
  '        - name: MISSING' \
  '          valueFrom:' \
  '            secretKeyRef:' \
  '              name: runtime-secret' \
  '              key: MISSING_KEY' \
  > "${fixture_dir}/manifests/missing.yaml"

if run_checker; then
  fail "missing key expected failure"
fi
grep -Fq -- 'Kubernetes Secret key preflight failed: runtime-secret.MISSING_KEY' "${output_file}" ||
  fail "missing key error was not reported: $(<"${output_file}")"
if grep -Fq -- 'redacted' "${output_file}"; then
  fail "Secret value was printed"
fi

echo "Live Kubernetes Secret key validator tests passed."
