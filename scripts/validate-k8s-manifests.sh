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
  "k8s/overlays/ec2-kubeadm/applications"
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

  if [[ "${package}" == "k8s/base/services/settlement" ]]; then
    required_patterns=(
      '^kind:[[:space:]]+CronJob$'
      '^[[:space:]]+name:[[:space:]]+settlement-weekly$'
      '^[[:space:]]+schedule:[[:space:]]+.*0 0 \* \* 1'
      '^[[:space:]]+timeZone:[[:space:]]+Asia/Seoul$'
      '^[[:space:]]+concurrencyPolicy:[[:space:]]+Forbid$'
      '^[[:space:]]+startingDeadlineSeconds:[[:space:]]+3600$'
      '^[[:space:]]+successfulJobsHistoryLimit:[[:space:]]+3$'
      '^[[:space:]]+failedJobsHistoryLimit:[[:space:]]+3$'
      '^[[:space:]]+backoffLimit:[[:space:]]+1$'
      '^[[:space:]]+activeDeadlineSeconds:[[:space:]]+7200$'
      '^[[:space:]]+restartPolicy:[[:space:]]+Never$'
      '^[[:space:]]+automountServiceAccountToken:[[:space:]]+false$'
      '^[[:space:]]+enableServiceLinks:[[:space:]]+false$'
      '^[[:space:]]+prompthub.io/node-pool:[[:space:]]+application$'
      '^[[:space:]]+- name:[[:space:]]+ghcr-pull-secret$'
      '^[[:space:]]+allowPrivilegeEscalation:[[:space:]]+false$'
      '^[[:space:]]+- ALL$'
      'settlement.execution.mode=cronjob'
      'spring.main.web-application-type=none'
      'settlement.manual-api.enabled=false'
      'eureka.client.enabled=false'
      'spring.cloud.discovery.enabled=false'
      'until wget -q -O /dev/null http://config:8888/actuator/health'
      'until nc -z postgres 5432'
      'until nc -z kafka 9092'
      'until nc -z order-service 9083'
      '^[[:space:]]+- name:[[:space:]]+POSTGRES_HOST$'
      '^[[:space:]]+- name:[[:space:]]+KAFKA_BOOTSTRAP_SERVERS$'
      '^[[:space:]]+- name:[[:space:]]+ORDER_GRPC_SERVER_PORT$'
      '^[[:space:]]+- name:[[:space:]]+JAVA_TOOL_OPTIONS$'
    )

    for pattern in "${required_patterns[@]}"; do
      if ! grep -Eq -- "${pattern}" "${rendered}"; then
        echo "missing settlement CronJob contract: ${pattern}" >&2
        exit 1
      fi
    done

    if grep -Eq '^kind:[[:space:]]+(Deployment|Service)$' "${rendered}"; then
      echo "settlement package must only expose a CronJob" >&2
      exit 1
    fi

    if grep -Eq 'EUREKA_CLIENT|http://discovery|startupProbe:|readinessProbe:|livenessProbe:|containerPort:' "${rendered}"; then
      echo "settlement CronJob contains an always-on service contract" >&2
      exit 1
    fi
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

  if [[ "${package}" == "k8s/overlays/ec2-kubeadm/applications" ]]; then
    deployment_count="$(awk '$1 == "kind:" && $2 == "Deployment" { count++ } END { print count + 0 }' "${rendered}")"
    service_count="$(awk '$1 == "kind:" && $2 == "Service" { count++ } END { print count + 0 }' "${rendered}")"
    cronjob_count="$(awk '$1 == "kind:" && $2 == "CronJob" { count++ } END { print count + 0 }' "${rendered}")"
    unexpected_kinds="$(awk '$1 == "kind:" && $2 != "Deployment" && $2 != "Service" && $2 != "CronJob" { print $2 }' "${rendered}" | sort -u)"

    if [[ "${deployment_count}" -ne 8 || "${service_count}" -ne 8 || "${cronjob_count}" -ne 1 ]]; then
      echo "application CD package must render 8 Deployments, 8 Services, and 1 CronJob" >&2
      exit 1
    fi

    if [[ -n "${unexpected_kinds}" ]]; then
      echo "application CD package contains manually managed kinds: ${unexpected_kinds}" >&2
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

bash "${ROOT_DIR}/scripts/validate-k8s-secret-contract.sh"
bash "${ROOT_DIR}/scripts/validate-k8s-cd-workflow.sh"

echo "Kubernetes manifest validation passed."
