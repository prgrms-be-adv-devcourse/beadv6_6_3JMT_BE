# Kubernetes 배포 매니페스트

이 문서는 `k8s/` 매니페스트를 렌더링·적용·검증·복구하는 실행 가이드다. 버전, 토폴로지, 포트, 리소스 이름, 배치와 용량 계약은 [Kubernetes 아키텍처 명세](../docs/architecture/kubernetes.md)를 유일한 원본으로 사용한다.

아키텍처 값을 이 README에서 별도로 정의하지 않는다. 값이 바뀌면 아키텍처 명세와 실제 매니페스트를 함께 수정하고, 실행 절차가 바뀔 때만 이 문서를 수정한다.

모든 명령은 저장소 루트에서 실행하며, `kubectl config current-context`가 대상 클러스터를 가리키는지 먼저 확인한다.

## 렌더링과 정적 검증

현재 구현된 각 상위 패키지는 독립적으로 렌더링할 수 있다.

```bash
kubectl kustomize k8s/addons/nginx-ingress
kubectl kustomize k8s/addons/elk
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

스크립트는 모든 패키지가 렌더링되는지, `latest` 이미지나 미정 placeholder가 없는지, 실제 Secret이 base에 포함되지 않는지를 확인한다. 자동 CD용 `applications` 패키지가 상시 서비스 Deployment·Service와 `CronJob/settlement-weekly`만 포함하는지도 검사한다. ELK의 Local PV, Gateway access 및 allowlist 애플리케이션 로그 경로, Gateway 14일·애플리케이션 7일 ILM, Product Service 전환 전 Elasticsearch security disabled 계약도 검사한다. Ingress Controller의 host network·이미지 digest·필수 인자와 EC2 kubeadm overlay의 Gateway Ingress를 검사하고 NodePort·LoadBalancer 회귀를 차단한다. 또한 Release CI, 재사용 애플리케이션 CD, 수동 인프라·Ingress 배포의 책임 경계와 기존 Compose CD의 자동 trigger 비활성 상태를 검사한다.

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

notification-service를 처음 배포하기 전에는 `postgres-secret`에
`NOTIFICATION_SERVICE_PASSWORD`를 추가하고 적용해야 한다. 기존 PostgreSQL PVC는 init
스크립트를 다시 실행하지 않으므로, schema·role·권한을 별도로 반영해야 한다. 정확한 순서와
검증 항목은 [notification-service PostgreSQL rollout](notification-service-rollout.md)을 따른다.
애플리케이션 CD는 배포 전에 모든 `secretKeyRef`의 key 존재 여부를 자동으로 확인하며,
Secret 값 자체는 출력하지 않는다. 운영자는 실제 Secret 파일과 기존 PVC의 DB 계약을
별도로 준비해야 한다.

Gateway access 로그용 ELK Secret은 별도 파일로 관리한다. `k8s/templates/elk-secrets.example.yaml`의 key와 객체 이름을 따르며, 실제 파일에는 Kibana 암호화 키와 Fluent Bit이 Logstash HTTP input에 인증할 비밀번호를 넣는다.

```text
/home/ubuntu/prompthub-secrets/elk-secret.yaml
mode: 600
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
  ai-secret \
  ghcr-pull-secret
```

하나라도 `NotFound`면 platform·services·gateway 또는 전체 EC2 kubeadm overlay를 실제 적용하지 않는다.

## ELK 배포

ELK는 Gateway access 로그와 애플리케이션 구조화 로그를 수집하는 add-on이다. 애플리케이션 allowlist는 `user-service`, `product-service`, `order-service`, `payment-service`, `admin-service`, `ai-service`, `settlement-service`, `notification-service`이며 `config`·`discovery`는 제외한다. Gateway access는 `gateway-access-*`에 14일, 애플리케이션 로그는 `application-logs-*`에 7일 보관한다. Elasticsearch가 Product Service 검색 인덱스도 함께 사용하므로, Product Service의 HTTPS·인증 전환이 완료되기 전까지 Elasticsearch HTTP와 security disabled 상태를 유지한다. 이 상태에서는 Elasticsearch와 Kibana를 외부에 공개하지 않고, ClusterIP 또는 운영자 SSH tunnel만 사용한다.

ELK를 처음 배포하고 Gateway 요청부터 Kibana 조회까지 직접 확인하려면 [AWS EC2 ELK 테스트 가이드](addons/elk/README.md)를 먼저 따른다.

Control Plane에서 적용 전에 Elasticsearch Local PV 경로와 커널 값을 준비한다.

```bash
sudo install -d -m 0750 /var/lib/prompthub/elasticsearch
sudo chown 1000:1000 /var/lib/prompthub/elasticsearch
sudo sysctl -w vm.max_map_count=1048576
sudo sh -c 'printf "vm.max_map_count=1048576\\n" > /etc/sysctl.d/99-prompthub-elasticsearch.conf'
sudo sysctl --system
```

`free -h`, `df -h`, `kubectl describe node k8s-control-plane`으로 PostgreSQL·Redis·ELK requests와 Control Plane/OS의 1.5GiB 이상 여유를 확인한다. 여유가 없으면 적용하지 않고 EC2 용량을 먼저 조정한다.

이미 `elk` namespace에 `deployment/elasticsearch`가 실행 중이면 아래의 새 StatefulSet을 바로 적용하지 않는다. 동일 이름의 Deployment와 StatefulSet이 공존하면 Service가 두 Pod를 모두 선택할 수 있고, 기존 `emptyDir` 데이터가 있는 Pod를 교체하면 `products-v1`을 포함한 기존 인덱스를 잃을 수 있다. 먼저 Product Service 담당자와 점검 시간을 정하고, Elasticsearch snapshot 또는 검증된 export·restore 절차로 기존 인덱스의 복구본을 만든 뒤 복구를 확인한다. 이 전환은 별도 운영 작업으로 승인한 뒤 수행하며, 자동 CD나 이 add-on의 일반 rollout에 포함하지 않는다.

새 설치이거나 위 전환 작업으로 기존 Elasticsearch Deployment가 안전하게 정리된 경우에만 다음을 실행한다.

```bash
kubectl create namespace elk --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f /home/ubuntu/prompthub-secrets/elk-secret.yaml
kubectl apply --dry-run=client -k k8s/addons/elk
kubectl apply --server-side --dry-run=server -k k8s/addons/elk
kubectl apply -k k8s/addons/elk
kubectl -n elk rollout restart deployment/logstash
kubectl -n elk rollout restart daemonset/fluent-bit
kubectl -n elk wait --for=create pod/elasticsearch-0 --timeout=5m
kubectl -n elk wait --for=condition=Ready pod/elasticsearch-0 --timeout=10m
kubectl -n elk rollout status deployment/logstash --timeout=10m
kubectl -n elk rollout status deployment/kibana --timeout=10m
kubectl -n elk rollout status daemonset/fluent-bit --timeout=10m
```

배포 후 정상 요청, 401, 404, 500, 503을 호출해 Kibana에서 `gateway.eventType: GATEWAY_ACCESS`와 응답 `X-Request-Id`가 일치하는지 확인한다. 애플리케이션 로그는 `Application Logs` Data View에서 같은 ID를 `requestId`로 검색하고 `service.name`, `level`, `kubernetes.container_name`을 확인한다. `gateway-access-*`는 14일, `application-logs-*`는 7일 후 삭제되며 `products-v1`에는 두 ILM 정책이 적용되지 않아야 한다.

최초 준비가 끝난 환경에서는 `k8s/addons/elk/**` 변경을 `develop`에 병합하면 `Release - Develop`이 기존 ELK 배포 절차를 자동 호출해 전체 allowlist를 반영한다. 명시적인 재적용이나 자동 실행 복구가 필요하면 수동 workflow의 `elk` 대상을 사용한다. notification-service를 포함한 모든 workload는 별도 collector 변경 없이 수집된다. 민감 키·Bearer/JWT·Cookie·password·secret·API key·body 값이 검색되지 않는지 확인한다. 이 과정에서 ELK Service는 계속 ClusterIP로 유지한다.

## GitHub Actions CI/CD

`develop` 자동 릴리스의 시작점은 `.github/workflows/release-develop.yml`이다. 이 workflow가 변경 모듈의 빌드·테스트, GHCR 이미지 발행과 release manifest 생성을 담당하고, 같은 실행 안에서 `.github/workflows/reusable-kubernetes-deploy.yml`을 호출해 애플리케이션을 배포한다. `k8s/addons/elk/**`가 변경되면 같은 release gate 뒤에서 `.github/workflows/cd-selfhosted-kubernetes.yml`의 ELK 절차를 reusable workflow로 호출한다. 이 workflow의 `workflow_dispatch`는 수동 인프라·Ingress 배포와 ELK 재적용에 사용한다. 기존 `.github/workflows/cd-selfhosted-compose.yml`의 `develop` push trigger는 비활성화하며 rollback이 필요할 때만 `workflow_dispatch`로 실행한다.

Self-hosted runner에는 다음 항목이 먼저 준비되어 있어야 한다.

```text
runner label: self-hosted, linux, deploy
kubectl
jq
KUBECONFIG=/home/ubuntu/.kube/config
```

`develop`에 push 또는 merge되면 변경된 모듈만 빌드·테스트한다. 모든 대상 모듈이 Release CI Gate를 통과한 뒤에만 같은 모듈의 이미지를 전체 Git SHA tag로 GHCR에 push한다. 이 tag는 실행 추적용이고, 실제 CD 입력은 빌드 결과에서 얻은 `repository@sha256:...` digest다. 상시 서비스는 Deployment를 순차 갱신하고, settlement-service는 `CronJob/settlement-weekly`의 Job template 이미지만 갱신한다.

Kubernetes 플랫폼·서비스·Gateway 매니페스트 변경도 서비스별로 감지한다. 예를 들어 `k8s/base/services/ai/**`만 바뀌면 AI 이미지를 다시 빌드하지 않고, 클러스터에 배포 중인 AI image ref를 유지한 채 AI 리소스만 server-side dry-run 후 적용하고 rollout을 기다린다. 코드와 매니페스트가 함께 바뀌면 새 digest를 주입한 매니페스트를 한 번만 적용한다. 클러스터에 대상 workload가 없는 최초 적용에만 base 매니페스트의 digest를 사용한다.

`k8s/overlays/ec2-kubeadm/applications/**` 공통 오버레이가 바뀌면 전체 애플리케이션이 대상이 되지만 동시에 적용하지 않고 고정된 서비스 순서로 하나씩 적용·검증한다. CI/CD workflow와 대상 계산 스크립트만 바뀐 push는 애플리케이션을 빌드하거나 배포하지 않는다. 공통 빌드 파일이 바뀌면 애플리케이션 전체를 테스트하고 이미지를 발행한다.

자동 매니페스트 적용 범위에는 Config, Discovery, 상시 비즈니스 서비스 7개와 API Gateway의 Deployment·Service, settlement 주간 CronJob, 그리고 `k8s/addons/elk/**`가 포함된다. StorageClass, 애플리케이션 인프라 PV/PVC, PostgreSQL, Redis, Kafka, Ingress Controller와 Gateway Ingress는 기존 수동 배포 경계를 유지한다. ELK 자동 배포는 namespace, Secret, Local PV 경로와 `vm.max_map_count`가 이미 준비된 환경만 대상으로 한다. 별도의 활성화 변수는 사용하지 않으므로 Secret, kubeconfig, 기존 Docker 중지와 cutover 준비가 끝난 뒤에만 Kubernetes CD가 포함된 PR을 `develop`에 머지한다.

상태 저장 인프라와 Ingress는 코드 push로 자동 적용하지 않는다. ELK의 최초 준비 또는 명시적인 재적용도 GitHub의 `Actions > CD - Self-hosted Kubernetes > Run workflow`에서 다음 target과 확인 문자열 `DEPLOY`를 사용한다.

| target | 적용 범위 | 실행 전 조건 |
|---|---|---|
| `infrastructure` | Namespace, StorageClass, PV/PVC, PostgreSQL, Redis, Kafka | Local PV 디렉터리와 `postgres-secret` 준비 |
| `ingress` | F5 NGINX Ingress Controller, Gateway Ingress | Docker Gateway 중지, Gateway Ready, 80·443·18080·18081 listener 반환 |
| `elk` | Elasticsearch, Logstash, Kibana, Fluent Bit 및 ILM bootstrap | Local PV 경로, `vm.max_map_count`, `elk-secret` 준비 |

`develop`의 현재 소스로 선택 서비스만 다시 발행하려면 GitHub의 `Actions > Release - Develop > Run workflow`에서 branch를 `develop`으로 선택한다. `release-services`에는 테스트·이미지 발행 대상을 쉼표로 입력하고, YAML도 다시 적용할 서비스만 `manifest-services`에 입력한 뒤 `confirmation`에 `RELEASE`를 입력한다. Config와 AI 이미지를 다시 발행하면서 AI YAML도 적용하는 값은 각각 `config,ai-service`, `ai-service`, `RELEASE`다. 이 방식은 과거 실패 실행을 재실행하지 않고 최신 `develop` SHA로 새 Release를 만든다.

워크플로는 Docker 컨테이너를 자동으로 중지하거나 삭제하지 않는다. 애플리케이션 rollout이 실패하면 대상 Deployment·ReplicaSet·Pod·이벤트와 노드 할당량을 먼저 로그에 남긴다. 그 뒤 적용 전 Pod template과 비교해 이번 실행에서 바뀐 기존 Deployment만 `kubectl rollout undo`로 복구하고, 이번 실행에서 처음 생성한 Deployment는 삭제한다. settlement CronJob은 적용 전 이미지를 별도로 기록해 실패 시 복구하며, 이번 실행에서 처음 생성했다면 CronJob만 삭제한다. Service 선언 복구가 필요하면 원인 커밋을 되돌린 뒤 CD를 다시 실행한다.

## 정산 주간 CronJob 확인

`settlement-weekly`는 매주 월요일 00:00 `Asia/Seoul`에 실행되며 이전 주 월요일~일요일을 정산한다.
동시 실행은 금지(`Forbid`)되고, 성공·실패 Job 이력은 각각 3개까지 남긴다.

```bash
kubectl get cronjob settlement-weekly -n prompthub
kubectl get jobs -n prompthub -l app.kubernetes.io/name=settlement-service
kubectl logs -n prompthub job/settlement-weekly-29123456 -c settlement-service
```

마지막 명령의 `settlement-weekly-29123456`은 예시다. 두 번째 명령에서 실제 생성된 Job 이름을
확인해 바꿔 실행한다. CD는 CronJob의 Job template 이미지만 갱신하며 정산 Job을 즉시 생성하지 않는다.
`kubectl create job --from=cronjob/settlement-weekly ...`를 이용한 수동 실행은 별도 운영 승인을 받은
경우에만 수행한다. 완료된 과거 주차의 누락 보정은 이 주간 CronJob의 범위 밖이다.

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

### kubelet 이미지 GC 운영 기준

두 EC2 노드는 containerd 이미지와 Local PV를 같은 루트 디스크에서 사용하므로 kubelet 기본
이미지 GC 상한 85%를 사용하지 않는다. Control Plane과 Worker의
`/var/lib/kubelet/config.yaml`에 다음 값을 유지한다.

```yaml
imageGCHighThresholdPercent: 60
imageGCLowThresholdPercent: 50
imageMinimumGCAge: 0s
imageMaximumGCAge: 0s
```

`imageMinimumGCAge: 0s`는 kubelet 설정 API 규칙에 따라 기본값 `2m`으로 해석된다. 따라서
사용하지 않은 지 2분이 지나지 않은 이미지는 디스크 임계값을 넘어도 GC 대상에서 제외된다.
`imageMaximumGCAge: 0s`는 최대 미사용 기간에 따른 강제 GC를 비활성화한다.

이미지 사용률이 60%를 넘으면 kubelet이 미사용 이미지를 정리해 50% 수준까지 낮춘다.
기간 기반 강제 삭제와 `crictl rmi --prune`, `ctr images rm` 같은 외부 정리 작업은 사용하지
않는다. Deployment는 `revisionHistoryLimit: 1`로 직전 ReplicaSet을 보존하고, 로컬 이미지가
GC된 경우에도 GHCR의 이전 digest를 다시 pull해 롤백한다.

설정은 Worker, Control Plane 순서로 한 노드씩 적용한다. 각 노드에서 기존 설정을 백업하고
kubelet만 재시작한 뒤 `systemctl is-active kubelet`, `kubectl get nodes`, 전체 Pod 상태를
검증한다. kubelet이 시작하지 않으면 백업을 복원하고 다음 노드 적용을 중단한다. drain,
StatefulSet 재시작, PVC/PV와 `/var/lib/prompthub` 삭제는 수행하지 않는다.

## 삭제와 복구 주의사항

- StatefulSet이나 Pod 삭제는 PVC/PV 삭제와 다르다.
- Local PV의 `Retain`은 데이터 디렉터리를 자동 삭제하지 않는다.
- PVC/PV 삭제는 정상 rollback 절차가 아니다.
- 데이터 초기화가 필요해도 StatefulSet, PVC/PV, 호스트 디렉터리의 관계를 확인한 뒤 별도 작업으로 수행한다.
- 현재 Docker Compose 컨테이너는 승인된 외부 트래픽 cutover 전까지 중단하거나 prune하지 않는다.
