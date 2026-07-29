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

remove_container_env() {
  local manifest="$1"
  local container_name="$2"
  local env_name="$3"
  local output="${manifest}.tmp"

  awk -v container_name="${container_name}" -v env_name="${env_name}" '
    function indentation(value) {
      match(value, /^[[:space:]]*/)
      return RLENGTH
    }
    {
      indent = indentation($0)

      if (skipping_env) {
        if ($0 !~ /^[[:space:]]*$/ && indent <= env_indent) {
          skipping_env = 0
        } else {
          next
        }
      }

      if (in_container && $0 !~ /^[[:space:]]*$/ && indent <= container_indent) {
        in_container = 0
      }

      if ($0 ~ "^[[:space:]]*- name:[[:space:]]+" container_name "[[:space:]]*$") {
        in_container = 1
        container_indent = indent
        print
        next
      }

      if (in_container &&
          $0 ~ "^[[:space:]]*- name:[[:space:]]+" env_name "[[:space:]]*$") {
        skipping_env = 1
        env_indent = indent
        next
      }

      print
    }
  ' "${manifest}" > "${output}"
  mv "${output}" "${manifest}"
}

new_fixture
baseline_output="${fixture_dir}/baseline.out"
assert_passes "baseline" "${baseline_output}"

printf '%s\n' \
  'apiVersion: v1' \
  'kind: Pod' \
  'metadata:' \
  '  name: reversed-order-fixture' \
  'spec:' \
  '  containers:' \
  '    - name: fixture' \
  '      image: busybox:1.37.0' \
  '      env:' \
  '        - name: REVERSED_ORDER' \
  '          valueFrom:' \
  '            secretKeyRef:' \
  '              key: REVERSED_MISSING_KEY' \
  '              name: runtime-secret' \
  > "${fixture_dir}/k8s/base/services/reversed-order.yaml"
reversed_order_output="${fixture_dir}/reversed-order.out"
assert_fails_with \
  "reversed name/key order" \
  "Kubernetes Secret contract validation failed: manifest secretKeyRef is missing from the example: runtime-secret.REVERSED_MISSING_KEY" \
  "${reversed_order_output}"
rm "${fixture_dir}/k8s/base/services/reversed-order.yaml"

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

cleanup
new_fixture

remove_container_env \
  "${fixture_dir}/k8s/base/services/ai/deployment.yaml" \
  "ai-service" \
  "KAFKA_BOOTSTRAP_SERVERS"

ai_missing_kafka_output="${fixture_dir}/ai-missing-kafka.out"
assert_fails_with \
  "ai service-scoped Kafka environment" \
  "Kubernetes Secret contract validation failed: deployed service ai-service requires KAFKA_BOOTSTRAP_SERVERS from config/src/main/resources/configs/ai-service.yml but k8s/base/services/ai/deployment.yaml does not inject it into container ai-service" \
  "${ai_missing_kafka_output}"

echo "Kubernetes Secret contract validator tests passed."
