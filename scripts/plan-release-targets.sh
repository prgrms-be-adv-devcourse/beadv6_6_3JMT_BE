#!/usr/bin/env bash

set -euo pipefail

: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

release_order=(
  config
  discovery
  user-service
  product-service
  order-service
  payment-service
  settlement-service
  admin-service
  ai-service
  notification-service
  apigateway
)

is_true() {
  [ "${1:-false}" = "true" ]
}

append_service() {
  local list="$1"
  local candidate="$2"
  case " $list " in
    *" $candidate "*) printf '%s' "$list" ;;
    *) printf '%s%s%s' "$list" "${list:+ }" "$candidate" ;;
  esac
}

require_service() {
  case "$1" in
    config|discovery|user-service|product-service|order-service|payment-service|settlement-service|admin-service|ai-service|notification-service|apigateway)
      ;;
    *)
      echo "허용되지 않은 Release 서비스: $1" >&2
      return 1
      ;;
  esac
}

ordered_json() {
  local selected=" $1 "
  local values=()
  local service

  for service in "${release_order[@]}"; do
    case "$selected" in
      *" $service "*) values+=("$service") ;;
    esac
  done

  if [ ${#values[@]} -eq 0 ]; then
    printf '[]'
  else
    printf '%s\n' "${values[@]}" | jq -R . | jq -s -c .
  fi
}

parse_manual_services() {
  local raw="$1"
  local parsed=""
  local parts=()
  local part

  if [ -z "$raw" ]; then
    printf ''
    return
  fi

  case ",$raw," in
    *,,*)
      echo "수동 Release 서비스에 빈 항목이 있습니다: $raw" >&2
      return 1
      ;;
  esac

  IFS=',' read -r -a parts <<< "$raw"
  for part in "${parts[@]}"; do
    part="$(printf '%s' "$part" | tr -d '[:space:]')"
    if ! require_service "$part"; then
      return 1
    fi
    parsed="$(append_service "$parsed" "$part")"
  done

  printf '%s' "$parsed"
}

test_services=""
image_services=""
manifest_services=""
config_consumer_services=""
deploy_services=""
apply_all_manifests=false

add_image_target() {
  test_services="$(append_service "$test_services" "$1")"
  image_services="$(append_service "$image_services" "$1")"
}

add_manifest_target() {
  manifest_services="$(append_service "$manifest_services" "$1")"
}

add_config_consumer() {
  config_consumer_services="$(append_service "$config_consumer_services" "$1")"
}

manual_requested=false
if [ -n "${MANUAL_CONFIRMATION:-}${MANUAL_RELEASE_SERVICES:-}${MANUAL_MANIFEST_SERVICES:-}" ]; then
  manual_requested=true
fi

if [ "$manual_requested" = "true" ]; then
  if [ "${MANUAL_CONFIRMATION:-}" != "RELEASE" ]; then
    echo "수동 Release는 confirmation에 RELEASE가 필요합니다." >&2
    exit 1
  fi

  test_services="$(parse_manual_services "${MANUAL_RELEASE_SERVICES:-}")"
  image_services="$test_services"
  manifest_services="$(parse_manual_services "${MANUAL_MANIFEST_SERVICES:-}")"

  if [[ " ${manifest_services} " == *" notification-service "* ]] \
    && [[ " ${image_services} " != *" notification-service "* ]]; then
    # The notification-service manifest has no previously published base image.
    # A manifest-only manual release must build it and inject that digest first.
    add_image_target notification-service
  fi
else
  if is_true "${SHARED_BUILD_CHANGED:-false}"; then
    for service in "${release_order[@]}"; do
      add_image_target "$service"
    done
  else
    is_true "${CONFIG_CHANGED:-false}" && add_image_target config
    is_true "${DISCOVERY_CHANGED:-false}" && add_image_target discovery
    is_true "${USER_SERVICE_CHANGED:-false}" && add_image_target user-service
    is_true "${PRODUCT_SERVICE_CHANGED:-false}" && add_image_target product-service
    is_true "${ORDER_SERVICE_CHANGED:-false}" && add_image_target order-service
    is_true "${PAYMENT_SERVICE_CHANGED:-false}" && add_image_target payment-service
    is_true "${SETTLEMENT_SERVICE_CHANGED:-false}" && add_image_target settlement-service
    is_true "${ADMIN_SERVICE_CHANGED:-false}" && add_image_target admin-service
    is_true "${AI_SERVICE_CHANGED:-false}" && add_image_target ai-service
    is_true "${NOTIFICATION_SERVICE_CHANGED:-false}" && add_image_target notification-service
    is_true "${APIGATEWAY_CHANGED:-false}" && add_image_target apigateway
  fi

  is_true "${CONFIG_MANIFEST_CHANGED:-false}" && add_manifest_target config
  is_true "${DISCOVERY_MANIFEST_CHANGED:-false}" && add_manifest_target discovery
  is_true "${USER_MANIFEST_CHANGED:-false}" && add_manifest_target user-service
  is_true "${PRODUCT_MANIFEST_CHANGED:-false}" && add_manifest_target product-service
  is_true "${ORDER_MANIFEST_CHANGED:-false}" && add_manifest_target order-service
  is_true "${PAYMENT_MANIFEST_CHANGED:-false}" && add_manifest_target payment-service
  is_true "${SETTLEMENT_MANIFEST_CHANGED:-false}" && add_manifest_target settlement-service
  is_true "${ADMIN_MANIFEST_CHANGED:-false}" && add_manifest_target admin-service
  is_true "${AI_MANIFEST_CHANGED:-false}" && add_manifest_target ai-service
  if is_true "${NOTIFICATION_MANIFEST_CHANGED:-false}"; then
    # notification-service has no previously published base image. Publish its
    # image whenever its manifest is introduced or changed so CD injects the
    # release digest instead of applying the bootstrap reference.
    add_image_target notification-service
    add_manifest_target notification-service
  fi
  is_true "${APIGATEWAY_MANIFEST_CHANGED:-false}" && add_manifest_target apigateway

  if is_true "${ALL_APPLICATION_MANIFESTS_CHANGED:-false}"; then
    apply_all_manifests=true
    manifest_services="${release_order[*]}"
  fi

  is_true "${USER_CONFIG_CHANGED:-false}" && add_config_consumer user-service
  is_true "${PRODUCT_CONFIG_CHANGED:-false}" && add_config_consumer product-service
  is_true "${ORDER_CONFIG_CHANGED:-false}" && add_config_consumer order-service
  is_true "${PAYMENT_CONFIG_CHANGED:-false}" && add_config_consumer payment-service
  is_true "${ADMIN_CONFIG_CHANGED:-false}" && add_config_consumer admin-service
  is_true "${AI_CONFIG_CHANGED:-false}" && add_config_consumer ai-service
  is_true "${NOTIFICATION_CONFIG_CHANGED:-false}" && add_config_consumer notification-service

  if is_true "${SHARED_CONFIG_CHANGED:-false}"; then
    for service in user-service product-service order-service payment-service admin-service ai-service notification-service apigateway; do
      add_config_consumer "$service"
    done
  fi
fi

deploy_services="$image_services"
for service in $manifest_services; do
  deploy_services="$(append_service "$deploy_services" "$service")"
done

test_matrix="$(ordered_json "$test_services")"
image_matrix="$(ordered_json "$image_services")"
manifest_matrix="$(ordered_json "$manifest_services")"
deploy_matrix="$(ordered_json "$deploy_services")"
config_consumer_matrix="$(ordered_json "$config_consumer_services")"
deploy=false
if [ "$deploy_matrix" != "[]" ] || [ "$config_consumer_matrix" != "[]" ]; then
  deploy=true
fi

{
  echo "test_matrix=$test_matrix"
  echo "image_matrix=$image_matrix"
  echo "manifest_matrix=$manifest_matrix"
  echo "deploy_matrix=$deploy_matrix"
  echo "config_consumer_matrix=$config_consumer_matrix"
  echo "apply_all_manifests=$apply_all_manifests"
  echo "deploy=$deploy"
} >> "$GITHUB_OUTPUT"
