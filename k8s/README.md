# Kubernetes 배포 매니페스트

이 문서는 `k8s/` 매니페스트를 렌더링·적용·검증·복구하는 실행 가이드다. 버전, 토폴로지, 포트, 리소스 이름, 배치와 용량 계약은 [Kubernetes 아키텍처 명세](../docs/architecture/kubernetes.md)를 유일한 원본으로 사용한다.

아키텍처 값을 이 README에서 별도로 정의하지 않는다. 값이 바뀌면 아키텍처 명세와 실제 매니페스트를 함께 수정하고, 실행 절차가 바뀔 때만 이 문서를 수정한다.

모든 명령은 저장소 루트에서 실행하며, `kubectl config current-context`가 대상 클러스터를 가리키는지 먼저 확인한다.

## 렌더링과 정적 검증

현재 구현된 각 상위 패키지는 독립적으로 렌더링할 수 있다.

```bash
kubectl kustomize k8s/addons/nginx-ingress
kubectl kustomize k8s/base/storage
kubectl kustomize k8s/base/infrastructure
kubectl kustomize k8s/base/platform
kubectl kustomize k8s/base/services
kubectl kustomize k8s/base/gateway
kubectl kustomize k8s/base
kubectl kustomize k8s/overlays/ec2-kubeadm/applications
kubectl kustomize k8s/overlays/ec2-kubeadm
```

저장소 루트에서 정적 검증을 실행한다.

```bash
bash scripts/validate-k8s-manifests.sh
```

스크립트는 모든 패키지가 렌더링되는지, `latest` 이미지나 미정 placeholder가 없는지, 실제 Secret이 base에 포함되지 않는지를 확인한다. 자동 CD용 `applications` 패키지가 Deployment 9개와 Service 9개만 포함하는지도 검사한다. Ingress Controller의 host network·이미지 digest·필수 인자와 EC2 kubeadm overlay의 Gateway Ingress를 검사하고 NodePort·LoadBalancer 회귀를 차단한다. 또한 Kubernetes CD의 자동·수동 배포 경계, rollback 명령과 기존 Compose CD의 자동 trigger 비활성 상태를 검사한다.

Kafka Pod는 `enableServiceLinks: false`를 유지한다. 이 값을 제거하면 Kubernetes가 `kafka` Service에서 `KAFKA_PORT=tcp://...`를 자동 생성하고, Confluent 이미지가 이를 레거시 설정으로 해석해 시작 단계에서 종료한다.

## Secret

Secret 객체, key와 저장 정책은 아키텍처 명세의 "설정과 Secret 명세"를 따른다. 실제 파일을 준비한 뒤 권한을 확인하고 적용한다.

```text
/home/ubuntu/prompthub-secrets/secret.yaml
mode: 600
```

키 이름은 `k8s/templates/runtime-values.example.yaml`을 참고한다. 예시 파일은 현재 Config Server placeholder, 애플리케이션 bootstrap, SDK와 image pull에서 실제 사용하는 key만 가짜 값으로 담으며 그대로 적용하면 안 된다. 아직 구현되지 않은 Spring AI용 Secret은 포함하지 않는다.

```bash
kubectl apply -f /home/ubuntu/prompthub-secrets/secret.yaml
```

Private GHCR 이미지를 배포하기 전에는 `read:packages` 권한을 가진 장기 credential로 `ghcr-pull-secret`을 준비한다. GitHub Actions 작업용 `GITHUB_TOKEN`은 장기 image pull credential로 사용하지 않는다.

Product의 AWS 인증은 Large EC2 인스턴스 역할을 사용한다. `product-secret`에는 `AWS_REGION`, `AWS_S3_BUCKET`만 넣고 정적 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`는 만들거나 주입하지 않는다.

애플리케이션 적용 전에는 다음 Secret 이름이 모두 존재해야 한다.

```bash
kubectl -n prompthub get secret \
  postgres-secret \
  runtime-secret \
  jwt-secret \
  payment-secret \
  product-secret \
  ghcr-pull-secret
```

하나라도 `NotFound`면 platform·services·gateway 또는 전체 EC2 kubeadm overlay를 실제 적용하지 않는다.

## GitHub Actions CD

Kubernetes 배포 워크플로는 `.github/workflows/cd-selfhosted-kubernetes.yml`이다. 기존 `.github/workflows/cd-selfhosted-compose.yml`의 `develop` push trigger는 비활성화하며 rollback이 필요할 때만 `workflow_dispatch`로 실행한다.

Self-hosted runner에는 다음 항목이 먼저 준비되어 있어야 한다.

```text
runner label: self-hosted, linux, deploy
kubectl
jq
KUBECONFIG=/home/ubuntu/.kube/config
```

`develop`에 push 또는 merge되면 변경된 애플리케이션 이미지를 빌드해 GHCR에 짧은 Git SHA tag로 push하고 Deployment를 순차 갱신한다. Kubernetes 플랫폼·서비스·Gateway 매니페스트 변경도 별도로 감지하며, 변경 시 `k8s/overlays/ec2-kubeadm/applications`를 새 image tag와 함께 server-side dry-run 후 자동 적용한다. 공통 빌드 파일, 애플리케이션 매니페스트 또는 Kubernetes CD 워크플로가 바뀌면 애플리케이션 전체를 대상으로 한다.

자동 매니페스트 적용 범위에는 Config, Discovery, 비즈니스 서비스 6개와 API Gateway의 Deployment·Service만 포함한다. StorageClass, PV/PVC, PostgreSQL, Redis, Kafka, Ingress Controller와 Gateway Ingress는 기존 수동 배포 경계를 유지한다. 별도의 활성화 변수는 사용하지 않으므로 Secret, kubeconfig, 기존 Docker 중지와 cutover 준비가 끝난 뒤에만 Kubernetes CD가 포함된 PR을 `develop`에 머지한다.

상태 저장 인프라와 Ingress는 코드 push로 자동 적용하지 않는다. GitHub의 `Actions > CD - Self-hosted Kubernetes > Run workflow`에서 다음 target과 확인 문자열 `DEPLOY`를 사용한다.

| target | 적용 범위 | 실행 전 조건 |
|---|---|---|
| `infrastructure` | Namespace, StorageClass, PV/PVC, PostgreSQL, Redis, Kafka | Local PV 디렉터리와 `postgres-secret` 준비 |
| `ingress` | F5 NGINX Ingress Controller, Gateway Ingress | Docker Gateway 중지, Gateway Ready, 80·443·18080·18081 listener 반환 |

워크플로는 Docker 컨테이너를 자동으로 중지하거나 삭제하지 않는다. 애플리케이션 rollout이 실패하면 적용 전 Pod template과 비교해 이번 실행에서 바뀐 기존 Deployment만 `kubectl rollout undo`로 복구하고, 이번 실행에서 처음 생성한 Deployment는 삭제한다. Service 선언 복구가 필요하면 원인 커밋을 되돌린 뒤 CD를 다시 실행한다.

## Local PV 호스트 디렉터리

Local PV 경로와 권한 기준은 아키텍처 명세의 "스토리지 명세"를 따른다. 매니페스트 적용 전에 각 노드에서 다음 디렉터리를 만든다.

Medium:

```bash
sudo install -d -m 0750 /var/lib/prompthub/postgres
sudo install -d -m 0750 /var/lib/prompthub/redis
sudo chown 70:70 /var/lib/prompthub/postgres
sudo chown 999:1000 /var/lib/prompthub/redis
```

Worker:

```bash
sudo install -d -m 0750 /var/lib/prompthub/kafka
sudo chown 1000:1000 /var/lib/prompthub/kafka
```

## 최초 적용

Kustomize의 파일 순서는 readiness를 보장하지 않는다. 아키텍처 명세의 "기동 순서와 의존성"에 따라 파동별로 적용하고 준비 상태를 확인한다. 현재 구현된 상태 저장 인프라는 다음 순서로 적용한다.

Control Plane에서 먼저 client와 server-side dry-run을 수행한다.

```bash
kubectl apply --dry-run=client -f k8s/base/namespace.yaml
kubectl apply --dry-run=client -k k8s/base/storage
kubectl apply --dry-run=client -k k8s/base/infrastructure

kubectl apply --server-side --dry-run=server -f k8s/base/namespace.yaml
kubectl apply --server-side --dry-run=server -k k8s/base/storage
kubectl apply --server-side --dry-run=server -k k8s/base/infrastructure
```

검증이 통과하면 GitHub Actions의 수동 `infrastructure` target을 실행한다. Control Plane에서 직접 복구하거나 점검할 때는 동일한 순서로 다음 명령을 실행할 수 있다.

```bash
kubectl apply -f k8s/base/namespace.yaml
kubectl apply -f /home/ubuntu/prompthub-secrets/secret.yaml
kubectl apply -k k8s/base/storage
kubectl apply -k k8s/base/infrastructure
```

## 애플리케이션과 Ingress 사전 검증

현재 Docker Compose Gateway는 Large의 host 80을 사용한다. F5 NGINX Ingress Controller도 `hostNetwork`로 같은 80을 사용하므로 둘을 동시에 실행할 수 없다. 2026-07-16 기준으로 Docker 12개와 Kubernetes Kafka가 실행 중인 Worker의 available memory도 약 2.3GiB이므로, 기존 Docker 전체가 실행 중일 때 Kubernetes 플랫폼·서비스·Gateway를 함께 올리지 않는다.

cutover 전에는 다음 client dry-run만 로컬 또는 Control Plane에서 수행한다.

```bash
kubectl apply --dry-run=client -k k8s/addons/nginx-ingress
kubectl apply --dry-run=client -k k8s/overlays/ec2-kubeadm
```

Controller namespace가 아직 없으면 빈 namespace만 먼저 만들 수 있다. 이 단계에서는 Pod가 생성되거나 포트가 열리지 않는다.

```bash
kubectl apply -f k8s/addons/nginx-ingress/namespace.yaml
```

이후 API Server 검증을 수행한다.

```bash
kubectl apply --server-side --dry-run=server -k k8s/addons/nginx-ingress
kubectl apply --server-side --dry-run=server -k k8s/overlays/ec2-kubeadm
```

`(server dry run)`이 아닌 실제 `created`가 표시되면 명령을 중단하고 `--dry-run=server` 누락 여부를 확인한다.

### Large 포트와 자원 preflight

Large에서 다음 listener와 메모리를 확인한다.

```bash
sudo ss -lntp \
  '( sport = :80 or sport = :443 or sport = :18080 or sport = :18081 )'

free -h
docker ps --format 'table {{.Names}}\t{{.Ports}}\t{{.Status}}'
```

Docker Gateway가 실행 중이면 80에 `docker-proxy`가 보이는 것이 현재의 정상 상태다. 이 상태에서는 `kubectl apply -k k8s/addons/nginx-ingress`를 실행하지 않는다.

### 승인된 cutover 순서

다음 단계는 서비스 중단과 메모리 회수를 포함하므로 별도 cutover 승인을 받은 뒤에만 진행한다.

1. 필요한 Secret과 GHCR pull credential을 준비한다.
2. Self-hosted runner의 `/home/ubuntu/.kube/config`, `kubectl`, `jq`와 클러스터 접근을 확인한다.
3. 기존 Docker 애플리케이션을 중지하고 80 listener와 메모리가 반환됐는지 확인한다.
4. 검토가 끝난 Kubernetes CD PR을 `develop`에 머지하고 `Deploy Applications` job이 성공할 때까지 기다린다.
5. `kubectl port-forward`로 API Gateway ClusterIP 경로를 먼저 검증한다.
6. GitHub Actions에서 수동 `ingress` target과 확인 문자열 `DEPLOY`를 실행한다.
7. Large 공인 DNS의 HTTP 80으로 로그인·상품 조회를 검증한다.

API Gateway 내부 검증 예시는 다음과 같다.

```bash
kubectl -n prompthub port-forward service/apigateway 18000:8000
curl --fail --show-error http://127.0.0.1:18000/actuator/health
```

Controller 적용 후에는 다음 상태를 확인한다.

```bash
kubectl -n nginx-ingress get daemonset,pods -o wide
kubectl get ingressclass nginx
kubectl -n prompthub get ingress apigateway
curl --fail --show-error http://13.209.136.116/actuator/health
```

실패하면 Controller DaemonSet을 먼저 중지해 host 80을 반환하고, Kubernetes 애플리케이션 Deployment를 축소한 뒤 기존 Compose CD를 수동 실행하거나 Docker 애플리케이션을 직접 다시 시작한다. PVC/PV와 현재 실행 중인 Kubernetes PostgreSQL·Redis·Kafka는 rollback 과정에서 삭제하지 않는다.

## 검증

각 wait는 최대 60초 단위로 실행한다. 시간 초과 시 무작정 반복하지 말고 `describe`, event와 log를 먼저 확인한다.

```bash
kubectl -n prompthub wait --for=condition=Ready pod/postgres-0 --timeout=60s
kubectl -n prompthub wait --for=condition=Ready pod/redis-0 --timeout=60s
kubectl -n prompthub wait --for=condition=Ready pod/kafka-0 --timeout=60s

kubectl -n prompthub get pods -o wide
kubectl get pv
kubectl -n prompthub get pvc
```

기능 검증:

```bash
kubectl -n prompthub exec postgres-0 -- sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select schema_name from information_schema.schemata where schema_name like '\''%_service'\'' order by 1"'

kubectl -n prompthub exec redis-0 -- redis-cli ping

kubectl -n prompthub exec kafka-0 -- kafka-topics \
  --bootstrap-server kafka:9092 \
  --create --if-not-exists \
  --topic k8s-smoke \
  --partitions 1 \
  --replication-factor 1
```

### Local PV 영속성 검증

영속성 검증은 새 Kubernetes 데이터에 테스트 marker를 기록한 뒤 Pod만 삭제하고 같은 값을 다시 읽는 방식으로 수행한다. PVC, PV와 호스트 디렉터리는 삭제하지 않는다.

```bash
kubectl -n prompthub delete pod postgres-0
kubectl -n prompthub wait --for=condition=Ready pod/postgres-0 --timeout=60s

kubectl -n prompthub delete pod redis-0
kubectl -n prompthub wait --for=condition=Ready pod/redis-0 --timeout=60s

kubectl -n prompthub delete pod kafka-0
kubectl -n prompthub wait --for=condition=Ready pod/kafka-0 --timeout=60s
```

## 이미지가 내려오는 방식

Kubernetes Pod의 `image:`에 레지스트리 주소와 불변 태그 또는 digest를 지정하면 kubelet이 containerd CRI를 통해 이미지를 pull한다. EC2에서 `docker pull`을 별도로 실행하지 않는다. 기존 Docker daemon의 이미지 namespace와 Kubernetes의 `k8s.io` containerd namespace가 다르므로 Docker에 이미지가 있다는 이유만으로 Kubernetes가 그 이미지를 사용할 수 있다고 가정하지 않는다.

## 삭제와 복구 주의사항

- StatefulSet이나 Pod 삭제는 PVC/PV 삭제와 다르다.
- Local PV의 `Retain`은 데이터 디렉터리를 자동 삭제하지 않는다.
- PVC/PV 삭제는 정상 rollback 절차가 아니다.
- 데이터 초기화가 필요해도 StatefulSet, PVC/PV, 호스트 디렉터리의 관계를 확인한 뒤 별도 작업으로 수행한다.
- 현재 Docker Compose 컨테이너는 승인된 외부 트래픽 cutover 전까지 중단하거나 prune하지 않는다.
