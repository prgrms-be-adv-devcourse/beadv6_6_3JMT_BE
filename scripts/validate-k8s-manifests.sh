#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! grep -Fxq 'ENV TZ=Asia/Seoul' "${ROOT_DIR}/Dockerfile" \
  || ! grep -Fxq 'ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]' "${ROOT_DIR}/Dockerfile"; then
  echo "application Docker image must use the Asia/Seoul JVM time zone" >&2
  exit 1
fi

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
  "k8s/base/services/ai"
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

require_literal_env() {
  local rendered="$1"
  local env_name="$2"
  local expected="$3"

  awk -v env_name="${env_name}" -v expected="${expected}" '
    $1 == "-" && $2 == "name:" {
      if (target && value_matches) {
        valid++
      }
      target = ($3 == env_name)
      if (target) {
        matches++
        value_matches = 0
      }
      next
    }
    target && $1 == "value:" {
      value = $2
      gsub(/^"|"$/, "", value)
      value_matches = (value == expected)
      next
    }
    END {
      if (target && value_matches) {
        valid++
      }
      exit !(matches == 1 && valid == 1)
    }
  ' "${rendered}"
}

require_secret_env() {
  local rendered="$1"
  local env_name="$2"
  local secret_name="$3"
  local secret_key="$4"

  awk -v env_name="${env_name}" -v secret_name="${secret_name}" -v secret_key="${secret_key}" '
    $1 == "-" && $2 == "name:" {
      if (target && secret_ref && secret_matches && key_matches) {
        valid++
      }
      target = ($3 == env_name)
      if (target) {
        matches++
        secret_ref = 0
        secret_matches = 0
        key_matches = 0
      }
      next
    }
    target && $1 == "secretKeyRef:" {
      secret_ref = 1
      next
    }
    target && secret_ref && $1 == "name:" {
      secret_matches = ($2 == secret_name)
      next
    }
    target && secret_ref && $1 == "key:" {
      key_matches = ($2 == secret_key)
      next
    }
    END {
      if (target && secret_ref && secret_matches && key_matches) {
        valid++
      }
      exit !(matches == 1 && valid == 1)
    }
  ' "${rendered}"
}

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

  if [[ "${package}" == "k8s/base/infrastructure/postgres" ]]; then
    required_patterns=(
      '^[[:space:]]+- -c$'
      '^[[:space:]]+- timezone=Asia/Seoul$'
      '^[[:space:]]+- name:[[:space:]]+TZ$'
      '^[[:space:]]+value:[[:space:]]+Asia/Seoul$'
    )

    for pattern in "${required_patterns[@]}"; do
      if ! grep -Eq -- "${pattern}" "${rendered}"; then
        echo "missing PostgreSQL KST contract: ${pattern}" >&2
        exit 1
      fi
    done
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

  if [[ "${package}" == "k8s/base/services/admin" ]]; then
    required_patterns=(
      '^[[:space:]]+- name:[[:space:]]+REDIS_HOST$'
      '^[[:space:]]+key:[[:space:]]+REDIS_HOST$'
      '^[[:space:]]+- name:[[:space:]]+REDIS_PORT$'
      '^[[:space:]]+key:[[:space:]]+REDIS_PORT$'
    )

    for pattern in "${required_patterns[@]}"; do
      if ! grep -Eq -- "${pattern}" "${rendered}"; then
        echo "missing admin Redis configuration contract: ${pattern}" >&2
        exit 1
      fi
    done
  fi

  if [[ "${package}" == "k8s/base/services/ai" ]]; then
    required_patterns=(
      '^kind:[[:space:]]+Deployment$'
      '^kind:[[:space:]]+Service$'
      '^[[:space:]]+name:[[:space:]]+ai-service$'
      'ghcr.io/prgrms-be-adv-devcourse/prompthub-ai-service@sha256:3b59e5e5f05e9bf84fe48365022684148c33530b4cb071fcee0bdc3a82f945ab'
      '^[[:space:]]+replicas:[[:space:]]+1$'
      'containerPort:[[:space:]]+18087$'
      '^[[:space:]]+port:[[:space:]]+8087$'
      'spring.grpc.client.channel.user-service.target=static://user-service:\$\(USER_GRPC_SERVER_PORT\)'
      'until wget -q -O /dev/null http://discovery:8761/actuator/health'
      'until wget -q -O /dev/null http://config:8888/actuator/health'
      'until nc -z redis 6379'
      '^[[:space:]]+- name:[[:space:]]+OPENAI_API_KEY$'
      '^[[:space:]]+- name:[[:space:]]+AI_USER_GRPC_TOKEN$'
      '^[[:space:]]+- name:[[:space:]]+OPENAI_REASONING_EFFORT$'
      '^[[:space:]]+- name:[[:space:]]+AI_SETTLEMENT_CHAT_ENABLED$'
      '^[[:space:]]+- name:[[:space:]]+AI_MAX_CONCURRENT_RUNS$'
      '^[[:space:]]+automountServiceAccountToken:[[:space:]]+false$'
      '^[[:space:]]+enableServiceLinks:[[:space:]]+false$'
      '^[[:space:]]+prompthub.io/node-pool:[[:space:]]+application$'
      '^[[:space:]]+- name:[[:space:]]+ghcr-pull-secret$'
      '^[[:space:]]+allowPrivilegeEscalation:[[:space:]]+false$'
      '^[[:space:]]+- ALL$'
    )

    for pattern in "${required_patterns[@]}"; do
      if ! grep -Eq -- "${pattern}" "${rendered}"; then
        echo "missing AI service contract: ${pattern}" >&2
        exit 1
      fi
    done

    if ! require_literal_env "${rendered}" OPENAI_REASONING_EFFORT low; then
      echo "AI reasoning effort must be low" >&2
      exit 1
    fi

    if ! require_literal_env "${rendered}" AI_SETTLEMENT_CHAT_ENABLED true; then
      echo "AI settlement chat feature must be enabled" >&2
      exit 1
    fi

    if ! require_literal_env "${rendered}" AI_MAX_CONCURRENT_RUNS 4; then
      echo "AI max concurrent runs must be 4" >&2
      exit 1
    fi

    if ! require_secret_env "${rendered}" OPENAI_API_KEY ai-secret OPENAI_API_KEY; then
      echo "AI OpenAI key must reference ai-secret.OPENAI_API_KEY" >&2
      exit 1
    fi

    if ! require_secret_env "${rendered}" AI_USER_GRPC_TOKEN ai-secret AI_USER_GRPC_TOKEN; then
      echo "AI gRPC token must reference ai-secret.AI_USER_GRPC_TOKEN" >&2
      exit 1
    fi

    if grep -Eq 'POSTGRES_|KAFKA_' "${rendered}"; then
      echo "AI service must not depend on PostgreSQL, Kafka, or User during Pod initialization" >&2
      exit 1
    fi

    init_container_block="$(sed -n -E '/^[[:space:]]+initContainers:/,/^[[:space:]]+nodeSelector:/p' "${rendered}")"
    if grep -Fq 'user-service' <<< "${init_container_block}"; then
      echo "AI init container must not hard-wait for User service" >&2
      exit 1
    fi

    if [[ "$(grep -Ec 'allowPrivilegeEscalation:[[:space:]]+false' <<< "${init_container_block}")" -ne 1 ]] ||
      [[ "$(grep -Ec '^[[:space:]]+- ALL$' <<< "${init_container_block}")" -ne 1 ]] ||
      [[ "$(grep -Ec 'allowPrivilegeEscalation:[[:space:]]+false' "${rendered}")" -ne 2 ]] ||
      [[ "$(grep -Ec '^[[:space:]]+- ALL$' "${rendered}")" -ne 2 ]]; then
      echo "AI application and init containers must both drop capabilities and disable privilege escalation" >&2
      exit 1
    fi
  fi

  if [[ "${package}" == "k8s/base/services/user" ]]; then
    required_patterns=(
      '^[[:space:]]+- name:[[:space:]]+AI_USER_GRPC_TOKEN$'
      '^[[:space:]]+- name:[[:space:]]+USER_SETTLEMENT_KAFKA_LISTENER_ENABLED$'
    )

    for pattern in "${required_patterns[@]}"; do
      if ! grep -Eq -- "${pattern}" "${rendered}"; then
        echo "missing User AI settlement contract: ${pattern}" >&2
        exit 1
      fi
    done

    if ! require_literal_env "${rendered}" USER_SETTLEMENT_KAFKA_LISTENER_ENABLED true; then
      echo "User settlement Kafka listener must be enabled" >&2
      exit 1
    fi

    if ! require_secret_env "${rendered}" AI_USER_GRPC_TOKEN ai-secret AI_USER_GRPC_TOKEN; then
      echo "User gRPC token must reference ai-secret.AI_USER_GRPC_TOKEN" >&2
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

    if [[ "${deployment_count}" -ne 9 || "${service_count}" -ne 9 || "${cronjob_count}" -ne 1 ]]; then
      echo "application CD package must render 9 Deployments, 9 Services, and 1 CronJob" >&2
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
