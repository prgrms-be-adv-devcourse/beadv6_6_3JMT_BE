#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGES=(
  "k8s/base/storage"
  "k8s/base/infrastructure/postgres"
  "k8s/base/infrastructure/redis"
  "k8s/base/infrastructure/kafka"
  "k8s/base/infrastructure"
  "k8s/base"
)

rendered_files=()

cleanup() {
  if ((${#rendered_files[@]} > 0)); then
    rm -f "${rendered_files[@]}"
  fi
}

trap cleanup EXIT

for package in "${PACKAGES[@]}"; do
  rendered="$(mktemp)"
  rendered_files+=("${rendered}")

  kubectl kustomize "${ROOT_DIR}/${package}" > "${rendered}"

  if grep -Eq 'image:[[:space:]]+[^[:space:]]+:latest([[:space:]]|$)' "${rendered}"; then
    echo "latest image is forbidden: ${package}" >&2
    exit 1
  fi

  if grep -Eq 'CHANGE_ME|TBD|TODO|<[^>]+>' "${rendered}"; then
    echo "unresolved placeholder found: ${package}" >&2
    exit 1
  fi

  if grep -Eq '^kind:[[:space:]]+Secret$' "${rendered}"; then
    echo "real Secret resources must not be part of the rendered base: ${package}" >&2
    exit 1
  fi

  if [[ "${package}" == "k8s/base/infrastructure/kafka" ]] \
    && ! grep -Eq '^[[:space:]]+enableServiceLinks:[[:space:]]+false$' "${rendered}"; then
    echo "Kafka must disable Kubernetes Service environment links" >&2
    exit 1
  fi
done

echo "Kubernetes manifest validation passed."
