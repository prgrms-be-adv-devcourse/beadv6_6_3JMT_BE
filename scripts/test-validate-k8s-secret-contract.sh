#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALIDATOR="${ROOT_DIR}/scripts/validate-k8s-secret-contract.sh"
fixture_dir=""

fail() {
  echo "Kubernetes Secret contract validator test failed: $1" >&2
  exit 1
}

cleanup() {
  if [[ -n "${fixture_dir}" && -d "${fixture_dir}" ]]; then
    rm -rf "${fixture_dir}"
  fi
}
trap cleanup EXIT

new_fixture() {
  fixture_dir="$(mktemp -d)"
  cp -R "${ROOT_DIR}/scripts" "${fixture_dir}/scripts"
  cp -R "${ROOT_DIR}/k8s" "${fixture_dir}/k8s"
  cp -R "${ROOT_DIR}/config" "${fixture_dir}/config"
}

run_validator() {
  local output_file="$1"
  if bash "${fixture_dir}/scripts/validate-k8s-secret-contract.sh" >"${output_file}" 2>&1; then
    return 0
  fi
  return 1
}

assert_passes() {
  local name="$1"
  local output_file="$2"
  if ! run_validator "${output_file}"; then
    fail "${name} expected success: $(<"${output_file}")"
  fi
}

assert_fails_with() {
  local name="$1"
  local expected="$2"
  local output_file="$3"

  if run_validator "${output_file}"; then
    fail "${name} expected failure"
  fi
  grep -Fq -- "${expected}" "${output_file}" ||
    fail "${name} expected '${expected}', got: $(<"${output_file}")"
}

new_fixture
baseline_output="${fixture_dir}/baseline.out"
assert_passes "baseline" "${baseline_output}"

printf '%s\n' 'server:' '  port: 18100' 'spring:' '  datasource:' '    password: ${FUTURE_SERVICE_PASSWORD}' \
  > "${fixture_dir}/config/src/main/resources/configs/future-service.yml"

unlisted_output="${fixture_dir}/unlisted.out"
assert_passes "unlisted profile" "${unlisted_output}"

printf '%s\n' '  - future' >> "${fixture_dir}/k8s/base/services/kustomization.yaml"
listed_output="${fixture_dir}/listed.out"
assert_fails_with \
  "listed profile" \
  "Kubernetes Secret contract validation failed: Config Server requires a missing example key: FUTURE_SERVICE_PASSWORD" \
  "${listed_output}"

mv \
  "${fixture_dir}/config/src/main/resources/configs/notification-service.yml" \
  "${fixture_dir}/config/src/main/resources/configs/notification-service.yml.disabled"
missing_profile_output="${fixture_dir}/missing-profile.out"
assert_fails_with \
  "missing deployed profile" \
  "Kubernetes Secret contract validation failed: missing Config Server profile for deployed service: notification-service" \
  "${missing_profile_output}"

echo "Kubernetes Secret contract validator tests passed."
