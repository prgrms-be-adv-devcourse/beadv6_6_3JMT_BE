#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLANNER="${ROOT_DIR}/scripts/plan-release-targets.sh"

fail() {
  echo "Release target planner test failed: $1" >&2
  exit 1
}

read_output() {
  local file="$1"
  local key="$2"
  sed -n "s/^${key}=//p" "$file"
}

run_case() {
  local name="$1"
  local expected_test="$2"
  local expected_image="$3"
  local expected_deploy="$4"
  local expected_manifests="$5"
  shift 5

  local output
  output="$(mktemp)"
  trap 'rm -f "$output"' RETURN

  env GITHUB_OUTPUT="$output" "$@" bash "$PLANNER"

  [ "$(read_output "$output" test_matrix)" = "$expected_test" ] ||
    fail "$name test_matrix"
  [ "$(read_output "$output" image_matrix)" = "$expected_image" ] ||
    fail "$name image_matrix"
  [ "$(read_output "$output" deploy)" = "$expected_deploy" ] ||
    fail "$name deploy"
  [ "$(read_output "$output" application_manifests_changed)" = "$expected_manifests" ] ||
    fail "$name application_manifests_changed"

  rm -f "$output"
  trap - RETURN
}

run_case \
  "settlement only" \
  '["settlement-service"]' \
  '["settlement-service"]' \
  true false \
  SETTLEMENT_SERVICE_CHANGED=true

run_case \
  "shared user grpc" \
  '["user-service","ai-service"]' \
  '["user-service","ai-service"]' \
  true false \
  USER_SERVICE_CHANGED=true AI_SERVICE_CHANGED=true

all_services='["config","discovery","user-service","product-service","order-service","payment-service","settlement-service","admin-service","ai-service","apigateway"]'
run_case \
  "shared build" \
  "$all_services" "$all_services" true false \
  SHARED_BUILD_CHANGED=true

run_case \
  "manifests only" \
  '[]' '[]' true true \
  APPLICATION_MANIFESTS_CHANGED=true

run_case \
  "docs only" \
  '[]' '[]' false false

echo "Release target planner tests passed."
