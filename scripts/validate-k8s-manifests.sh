#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGES=(
  "k8s/addons/nginx-ingress"
  "k8s/base/storage"
  "k8s/base/infrastructure/postgres"
  "k8s/base/infrastructure/redis"
  "k8s/base/infrastructure/kafka"
  "k8s/base/infrastructure"
  "k8s/base/platform/discovery"
  "k8s/base/platform/config"
  "k8s/base/platform"
  "k8s/base/services/user"
  "k8s/base/services/product"
  "k8s/base/services/order"
  "k8s/base/services/payment"
  "k8s/base/services/settlement"
  "k8s/base/services/admin"
  "k8s/base/services"
  "k8s/base/gateway"
  "k8s/base"
  "k8s/overlays/ec2-kubeadm"
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

  if ! awk '
    BEGIN { RS = "---"; invalid = 0 }
    /kind:[[:space:]]+Deployment/ && !/revisionHistoryLimit:[[:space:]]+1/ {
      invalid = 1
    }
    END { exit invalid }
  ' "${rendered}"; then
    echo "every Deployment must retain exactly one previous ReplicaSet: ${package}" >&2
    exit 1
  fi

  if [[ "${package}" == "k8s/base/infrastructure/kafka" ]] \
    && ! grep -Eq '^[[:space:]]+enableServiceLinks:[[:space:]]+false$' "${rendered}"; then
    echo "Kafka must disable Kubernetes Service environment links" >&2
    exit 1
  fi

  if [[ "${package}" == "k8s/addons/nginx-ingress" ]]; then
    required_patterns=(
      '^kind:[[:space:]]+DaemonSet$'
      '^[[:space:]]+hostNetwork:[[:space:]]+true$'
      '^[[:space:]]+dnsPolicy:[[:space:]]+ClusterFirstWithHostNet$'
      'nginx/nginx-ingress:5.5.1@sha256:85b835a6a8d1958f51ce94f0f1e7af833e63731add98eca1d15b11b75ebd50c6'
      'enable-custom-resources=false'
      'allow-empty-ingress-host'
      'watch-namespace=prompthub'
      'enable-telemetry-reporting=false'
      'enable-leader-election=false'
      'nginx-status-port=18080'
      'ready-status-port=18081'
    )

    for pattern in "${required_patterns[@]}"; do
      if ! grep -Eq "${pattern}" "${rendered}"; then
        echo "missing ingress controller contract: ${pattern}" >&2
        exit 1
      fi
    done

    if grep -Eq '^kind:[[:space:]]+Service$' "${rendered}"; then
      echo "ingress controller must not render an external Service" >&2
      exit 1
    fi
  fi

  if [[ "${package}" == "k8s/overlays/ec2-kubeadm" ]]; then
    required_patterns=(
      '^kind:[[:space:]]+Ingress$'
      '^[[:space:]]+ingressClassName:[[:space:]]+nginx$'
      '^[[:space:]]+name:[[:space:]]+apigateway$'
      '^[[:space:]]+number:[[:space:]]+8000$'
      '^[[:space:]]+type:[[:space:]]+ClusterIP$'
    )

    for pattern in "${required_patterns[@]}"; do
      if ! grep -Eq "${pattern}" "${rendered}"; then
        echo "missing ec2-kubeadm ingress contract: ${pattern}" >&2
        exit 1
      fi
    done

    if grep -Eq '^[[:space:]]+(type:[[:space:]]+(NodePort|LoadBalancer)|nodePort:)' "${rendered}"; then
      echo "public NodePort or LoadBalancer is forbidden" >&2
      exit 1
    fi
  fi
done

bash "${ROOT_DIR}/scripts/validate-k8s-cd-workflow.sh"

echo "Kubernetes manifest validation passed."
