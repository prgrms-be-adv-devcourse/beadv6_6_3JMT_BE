#!/usr/bin/env bash

set -euo pipefail

: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

is_true() {
  [ "${1:-false}" = "true" ]
}

services=()

add_service() {
  local candidate="$1"
  local service
  for service in "${services[@]:-}"; do
    [ "$service" = "$candidate" ] && return
  done
  services+=("$candidate")
}

if is_true "${SHARED_BUILD_CHANGED:-false}"; then
  services=(
    config
    discovery
    user-service
    product-service
    order-service
    payment-service
    settlement-service
    admin-service
    ai-service
    apigateway
  )
else
  is_true "${CONFIG_CHANGED:-false}" && add_service config
  is_true "${DISCOVERY_CHANGED:-false}" && add_service discovery
  is_true "${USER_SERVICE_CHANGED:-false}" && add_service user-service
  is_true "${PRODUCT_SERVICE_CHANGED:-false}" && add_service product-service
  is_true "${ORDER_SERVICE_CHANGED:-false}" && add_service order-service
  is_true "${PAYMENT_SERVICE_CHANGED:-false}" && add_service payment-service
  is_true "${SETTLEMENT_SERVICE_CHANGED:-false}" && add_service settlement-service
  is_true "${ADMIN_SERVICE_CHANGED:-false}" && add_service admin-service
  is_true "${AI_SERVICE_CHANGED:-false}" && add_service ai-service
  is_true "${APIGATEWAY_CHANGED:-false}" && add_service apigateway
fi

if [ ${#services[@]} -eq 0 ]; then
  matrix='[]'
else
  matrix="$(printf '%s\n' "${services[@]}" | jq -R . | jq -s -c .)"
fi

manifests_changed="${APPLICATION_MANIFESTS_CHANGED:-false}"
deploy=false
if [ "$matrix" != '[]' ] || is_true "$manifests_changed"; then
  deploy=true
fi

{
  echo "test_matrix=$matrix"
  echo "image_matrix=$matrix"
  echo "deploy=$deploy"
  echo "application_manifests_changed=$manifests_changed"
} >> "$GITHUB_OUTPUT"
