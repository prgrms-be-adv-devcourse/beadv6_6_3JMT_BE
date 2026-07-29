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
  local expected_manifest="$4"
  local expected_deploy_matrix="$5"
  local expected_config_consumers="$6"
  local expected_apply_all="$7"
  local expected_deploy="$8"
  shift 8

  local output
  output="$(mktemp)"
  trap 'rm -f "$output"' RETURN

  env GITHUB_OUTPUT="$output" "$@" bash "$PLANNER"

  [ "$(read_output "$output" test_matrix)" = "$expected_test" ] ||
    fail "$name test_matrix"
  [ "$(read_output "$output" image_matrix)" = "$expected_image" ] ||
    fail "$name image_matrix"
  [ "$(read_output "$output" manifest_matrix)" = "$expected_manifest" ] ||
    fail "$name manifest_matrix"
  [ "$(read_output "$output" deploy_matrix)" = "$expected_deploy_matrix" ] ||
    fail "$name deploy_matrix"
  [ "$(read_output "$output" config_consumer_matrix)" = "$expected_config_consumers" ] ||
    fail "$name config_consumer_matrix"
  [ "$(read_output "$output" apply_all_manifests)" = "$expected_apply_all" ] ||
    fail "$name apply_all_manifests"
  [ "$(read_output "$output" deploy)" = "$expected_deploy" ] ||
    fail "$name deploy"

  rm -f "$output"
  trap - RETURN
}

run_failure_case() {
  local name="$1"
  shift

  local output
  output="$(mktemp)"
  trap 'rm -f "$output"' RETURN

  if env GITHUB_OUTPUT="$output" "$@" bash "$PLANNER"; then
    fail "$name expected failure"
  fi

  rm -f "$output"
  trap - RETURN
}

all_services='["config","discovery","user-service","product-service","order-service","payment-service","settlement-service","admin-service","ai-service","notification-service","apigateway"]'

run_case \
  "settlement only" \
  '["settlement-service"]' \
  '["settlement-service"]' \
  '[]' \
  '["settlement-service"]' \
  '[]' false true \
  SETTLEMENT_SERVICE_CHANGED=true

run_case \
  "shared user grpc" \
  '["user-service","ai-service"]' \
  '["user-service","ai-service"]' \
  '[]' \
  '["user-service","ai-service"]' \
  '[]' false true \
  USER_SERVICE_CHANGED=true AI_SERVICE_CHANGED=true

run_case \
  "shared build" \
  "$all_services" \
  "$all_services" \
  '[]' \
  "$all_services" \
  '[]' false true \
  SHARED_BUILD_CHANGED=true

run_case \
  "ai manifest only" \
  '[]' \
  '[]' \
  '["ai-service"]' \
  '["ai-service"]' \
  '[]' false true \
  AI_MANIFEST_CHANGED=true

run_case \
  "ai code and manifest" \
  '["ai-service"]' \
  '["ai-service"]' \
  '["ai-service"]' \
  '["ai-service"]' \
  '[]' false true \
  AI_SERVICE_CHANGED=true AI_MANIFEST_CHANGED=true

run_case \
  "shared application overlay" \
  '[]' \
  '[]' \
  "$all_services" \
  "$all_services" \
  '[]' true true \
  ALL_APPLICATION_MANIFESTS_CHANGED=true

run_case \
  "ai config profile" \
  '["config"]' \
  '["config"]' \
  '[]' \
  '["config"]' \
  '["ai-service"]' false true \
  CONFIG_CHANGED=true AI_CONFIG_CHANGED=true

run_case \
  "notification code and manifest" \
  '["notification-service"]' \
  '["notification-service"]' \
  '["notification-service"]' \
  '["notification-service"]' \
  '[]' false true \
  NOTIFICATION_SERVICE_CHANGED=true NOTIFICATION_MANIFEST_CHANGED=true

run_case \
  "notification config profile" \
  '["config"]' \
  '["config"]' \
  '[]' \
  '["config"]' \
  '["notification-service"]' false true \
  CONFIG_CHANGED=true NOTIFICATION_CONFIG_CHANGED=true

run_case \
  "notification code manifest and config" \
  '["config","notification-service"]' \
  '["config","notification-service"]' \
  '["notification-service"]' \
  '["config","notification-service"]' \
  '["notification-service"]' false true \
  CONFIG_CHANGED=true \
  NOTIFICATION_SERVICE_CHANGED=true \
  NOTIFICATION_MANIFEST_CHANGED=true \
  NOTIFICATION_CONFIG_CHANGED=true

run_case \
  "shared config" \
  '["config"]' \
  '["config"]' \
  '[]' \
  '["config"]' \
  '["user-service","product-service","order-service","payment-service","admin-service","ai-service","notification-service","apigateway"]' \
  false true \
  CONFIG_CHANGED=true SHARED_CONFIG_CHANGED=true

run_case \
  "pipeline only" \
  '[]' \
  '[]' \
  '[]' \
  '[]' \
  '[]' false false \
  PIPELINE_CHANGED=true

run_case \
  "manual config and ai replay" \
  '["config","ai-service"]' \
  '["config","ai-service"]' \
  '["ai-service"]' \
  '["config","ai-service"]' \
  '[]' false true \
  MANUAL_CONFIRMATION=RELEASE \
  MANUAL_RELEASE_SERVICES=config,ai-service \
  MANUAL_MANIFEST_SERVICES=ai-service

run_case \
  "manual notification manifest bootstrap" \
  '["config","notification-service"]' \
  '["config","notification-service"]' \
  '["notification-service"]' \
  '["config","notification-service"]' \
  '[]' false true \
  MANUAL_CONFIRMATION=RELEASE \
  MANUAL_RELEASE_SERVICES=config \
  MANUAL_MANIFEST_SERVICES=notification-service

run_case \
  "docs only" \
  '[]' \
  '[]' \
  '[]' \
  '[]' \
  '[]' false false

run_failure_case \
  "manual confirmation" \
  MANUAL_CONFIRMATION=WRONG \
  MANUAL_RELEASE_SERVICES=config,ai-service

run_failure_case \
  "manual unknown service" \
  MANUAL_CONFIRMATION=RELEASE \
  MANUAL_RELEASE_SERVICES=config,unknown

run_failure_case \
  "manual empty item" \
  MANUAL_CONFIRMATION=RELEASE \
  MANUAL_RELEASE_SERVICES=config,,ai-service

echo "Release target planner tests passed."
