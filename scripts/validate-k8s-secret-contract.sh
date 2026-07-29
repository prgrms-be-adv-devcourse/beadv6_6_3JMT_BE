#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="${ROOT_DIR}/k8s/templates/runtime-values.example.yaml"
MANIFEST_ROOT="${ROOT_DIR}/k8s/base"
CONFIG_ROOT="${ROOT_DIR}/config/src/main/resources/configs"
SERVICES_KUSTOMIZATION="${MANIFEST_ROOT}/services/kustomization.yaml"

fail() {
  echo "Kubernetes Secret contract validation failed: $1" >&2
  exit 1
}

[[ -f "${TEMPLATE}" ]] || fail "missing k8s/templates/runtime-values.example.yaml"
[[ -f "${SERVICES_KUSTOMIZATION}" ]] || fail "missing k8s/base/services/kustomization.yaml"

declare -a required_config_files=("${CONFIG_ROOT}/application.yml")
declare -a seen_services=()
declare -a deployed_service_contracts=()

service_entries="$(
  awk '
    /^resources:[[:space:]]*$/ {
      in_resources = 1
      next
    }
    in_resources && /^[^[:space:]]/ {
      in_resources = 0
    }
    in_resources && /^[[:space:]]*-[[:space:]]*/ {
      value = $0
      sub(/^[[:space:]]*-[[:space:]]*/, "", value)
      sub(/[[:space:]]+#.*$/, "", value)
      if (value != "") {
        print value
      }
    }
  ' "${SERVICES_KUSTOMIZATION}"
)"

while IFS= read -r service; do
  [[ -n "${service}" ]] || continue
  service="${service#./}"
  service="${service%/}"

  if [[ "${service}" == *"/"* || "${service}" == "." || "${service}" == ".." ]]; then
    fail "unsupported service resource in k8s/base/services/kustomization.yaml: ${service}"
  fi
  if [[ ${#seen_services[@]} -gt 0 ]]; then
    for seen_service in "${seen_services[@]}"; do
      if [[ "${seen_service}" == "${service}" ]]; then
        fail "duplicate service resource in k8s/base/services/kustomization.yaml: ${service}"
      fi
    done
  fi

  seen_services+=("${service}")
  profile="${CONFIG_ROOT}/${service}-service.yml"
  [[ -f "${profile}" ]] || fail "missing Config Server profile for deployed service: ${service}-service"
  required_config_files+=("${profile}")
done <<< "${service_entries}"

template_secret_pairs="$(
  awk '
    /^---$/ {
      secret_name = ""
      in_metadata = 0
      in_string_data = 0
      next
    }
    /^metadata:$/ {
      in_metadata = 1
      next
    }
    in_metadata && /^  name:[[:space:]]+/ {
      secret_name = $2
      in_metadata = 0
      next
    }
    /^stringData:$/ {
      in_string_data = 1
      next
    }
    in_string_data && /^  [A-Za-z0-9_.-]+:/ {
      key = $1
      sub(/:$/, "", key)
      print secret_name "|" key
      next
    }
    in_string_data && /^[^[:space:]]/ {
      in_string_data = 0
    }
  ' "${TEMPLATE}" | sort -u
)"

template_keys="$(
  printf '%s\n' "${template_secret_pairs}" | cut -d '|' -f 2 | sort -u
)"

manifest_secret_pairs="$(
  while IFS= read -r manifest; do
    awk '
      function emit_pair() {
        if (secret_name != "" && secret_key != "") {
          print secret_name "|" secret_key
        }
      }
      function close_secret_ref() {
        emit_pair()
        in_secret_ref = 0
        secret_indent = -1
        secret_name = ""
        secret_key = ""
      }
      /secretKeyRef:/ {
        close_secret_ref()
        match($0, /^[[:space:]]*/)
        secret_indent = RLENGTH
        in_secret_ref = 1
        next
      }
      in_secret_ref {
        match($0, /^[[:space:]]*/)
        if ($0 !~ /^[[:space:]]*$/ && RLENGTH <= secret_indent) {
          close_secret_ref()
          next
        }
        if ($1 == "name:") {
          secret_name = $2
        } else if ($1 == "key:") {
          secret_key = $2
        }
      }
      END { close_secret_ref() }
    ' "${manifest}"
  done < <(find "${MANIFEST_ROOT}" -type f \( -name '*.yaml' -o -name '*.yml' \) | sort) | sort -u
)"

manifest_pull_secret_names="$(
  grep -R -h -A1 'imagePullSecrets:' "${MANIFEST_ROOT}" \
    | awk '$1 == "-" && $2 == "name:" { print $3 }' \
    | sort -u
)"

[[ -n "${template_secret_pairs}" ]] || fail "no stringData keys found"

ai_secret_keys="$(
  printf '%s\n' "${template_secret_pairs}" \
    | awk -F '|' '$1 == "ai-secret" { print $2 }' \
    | sort
)"
expected_ai_secret_keys=$'AI_USER_GRPC_TOKEN\nOPENAI_API_KEY'

if [[ "${ai_secret_keys}" != "${expected_ai_secret_keys}" ]]; then
  fail "ai-secret must contain exactly AI_USER_GRPC_TOKEN and OPENAI_API_KEY"
fi

required_config_keys="$(
  grep -hoE '\$\{[A-Z][A-Z0-9_]*\}' "${required_config_files[@]}" \
    | sed -e 's/^${//' -e 's/}$//' \
    | sort -u
)"

has_template_key() {
  local expected="$1"
  grep -Fxq -- "${expected}" <<< "${template_keys}"
}

has_template_secret_pair() {
  local expected="$1"
  grep -Fxq -- "${expected}" <<< "${template_secret_pairs}"
}

has_manifest_secret_pair() {
  local expected="$1"
  grep -Fxq -- "${expected}" <<< "${manifest_secret_pairs}"
}

is_required_config_key() {
  local key="$1"
  grep -Fxq -- "${key}" <<< "${required_config_keys}"
}

is_approved_non_config_key() {
  case "$1" in
    .dockerconfigjson | \
      CORS_ALLOWED_ORIGINS | \
      ORDER_SERVICE_PASSWORD | \
      PAYMENT_GRPC_SERVER_PORT | \
      PAYMENT_SERVICE_PASSWORD | \
      PRODUCT_SERVICE_PASSWORD | \
      SETTLEMENT_SERVICE_PASSWORD | \
      SPRING_CONFIG_IMPORT | \
      USER_SERVICE_PASSWORD)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

while IFS= read -r pair; do
  secret_name="${pair%%|*}"
  key="${pair#*|}"

  if [[ "${key}" == ".dockerconfigjson" ]]; then
    if ! grep -R -Fq 'name: ghcr-pull-secret' "${MANIFEST_ROOT}"; then
      fail "unused example key: ghcr-pull-secret.${key}"
    fi
  elif ! has_manifest_secret_pair "${pair}"; then
    fail "unused or mismatched example key: ${secret_name}.${key}"
  fi

  if ! is_required_config_key "${key}" && ! is_approved_non_config_key "${key}"; then
    fail "example key is neither a required Config placeholder nor an approved runtime key: ${key}"
  fi
done <<< "${template_secret_pairs}"

while IFS= read -r pair; do
  [[ -n "${pair}" ]] || continue
  if ! has_template_secret_pair "${pair}"; then
    fail "manifest secretKeyRef is missing from the example: ${pair%%|*}.${pair#*|}"
  fi
done <<< "${manifest_secret_pairs}"

while IFS= read -r secret_name; do
  [[ -n "${secret_name}" ]] || continue
  if ! has_template_secret_pair "${secret_name}|.dockerconfigjson"; then
    fail "imagePullSecret is missing from the example: ${secret_name}"
  fi
done <<< "${manifest_pull_secret_names}"

while IFS= read -r key; do
  [[ -n "${key}" ]] || continue
  if ! has_template_key "${key}"; then
    fail "Config Server requires a missing example key: ${key}"
  fi
done <<< "${required_config_keys}"

for service in "${seen_services[@]}"; do
  profile_relative="config/src/main/resources/configs/${service}-service.yml"
  workload_relative="k8s/base/services/${service}/deployment.yaml"
  if [[ "${service}" == "settlement" ]]; then
    workload_relative="k8s/base/services/settlement/cronjob.yaml"
  fi
  workload="${ROOT_DIR}/${workload_relative}"
  [[ -f "${workload}" ]] ||
    fail "missing Kubernetes workload for deployed service: ${service}-service"

  deployed_service_contracts+=(
    "${service}-service|${profile_relative}|${workload_relative}"
  )
done

application_env_names() {
  local manifest="$1"
  local container_name="$2"

  awk -v target="${container_name}" '
    function indentation(value) {
      match(value, /^[[:space:]]*/)
      return RLENGTH
    }
    {
      indent = indentation($0)

      if (in_container && $0 !~ /^[[:space:]]*$/ && indent <= container_indent) {
        in_container = 0
        in_env = 0
      }

      if ($0 ~ "^[[:space:]]*- name:[[:space:]]+" target "[[:space:]]*$") {
        in_container = 1
        in_env = 0
        container_indent = indent
        next
      }

      if (in_container && $0 ~ "^[[:space:]]+env:[[:space:]]*$") {
        in_env = 1
        env_indent = indent
        next
      }

      if (in_env && $0 !~ /^[[:space:]]*$/ && indent <= env_indent) {
        in_env = 0
      }

      if (in_env &&
          $0 ~ "^[[:space:]]*- name:[[:space:]]+[A-Z][A-Z0-9_]*[[:space:]]*$") {
        print $3
      }
    }
  ' "${manifest}" | sort -u
}

for contract in "${deployed_service_contracts[@]}"; do
  IFS='|' read -r service_name profile_relative workload_relative <<< "${contract}"
  profile="${ROOT_DIR}/${profile_relative}"
  workload="${ROOT_DIR}/${workload_relative}"

  service_required_keys="$(
    grep -hoE '\$\{[A-Z][A-Z0-9_]*\}' "${profile}" \
      | sed -e 's/^${//' -e 's/}$//' \
      | sort -u ||
      true
  )"
  service_env_names="$(application_env_names "${workload}" "${service_name}")"

  while IFS= read -r key; do
    [[ -n "${key}" ]] || continue
    if ! grep -Fxq -- "${key}" <<< "${service_env_names}"; then
      fail "deployed service ${service_name} requires ${key} from ${profile_relative} but ${workload_relative} does not inject it into container ${service_name}"
    fi
  done <<< "${service_required_keys}"
done

echo "Kubernetes Secret contract validation passed."
