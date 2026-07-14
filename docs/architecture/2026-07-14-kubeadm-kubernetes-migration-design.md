# kubeadm 기반 Kubernetes 배포 전환 설계

> 상태: 대화에서 승인된 설계
> 관련 이슈: [#340](https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/issues/340)
> 작성일: 2026-07-14

## 1. 요약

현재 단일 EC2의 Docker Compose에서 실행하는 PromptHub 백엔드를 두 대의 EC2에 직접 설치한 Kubernetes 클러스터로 옮긴다. 관리형 Kubernetes는 사용하지 않는다. Ubuntu에 containerd, kubeadm, kubelet, kubectl과 Flannel을 직접 설치하면서 클러스터 부트스트랩, Pod 네트워크, 스케줄링, StatefulSet, PV/PVC, Secret, probe와 배포·복구 절차를 학습하는 것이 핵심이다.

Medium EC2는 단일 Control Plane이면서 PostgreSQL과 Redis를 실행한다. Large EC2는 단일 Worker이며 Kafka, Spring Cloud 코어, 여섯 비즈니스 서비스, API Gateway와 Spring AI 서비스를 실행한다. 모든 워크로드는 초기 replica를 1개로 제한한다. 이 구성은 학습 환경이며 노드 장애 고가용성을 제공하지 않는다.

## 2. 목표

- SSH로 두 EC2에 접속해 Kubernetes 구성 요소를 직접 설치한다.
- `kubeadm init`과 `kubeadm join`으로 단일 Control Plane·단일 Worker 클러스터를 만든다.
- Flannel VXLAN으로 두 노드의 Pod 통신을 구성한다.
- Compose의 PostgreSQL·Redis·Kafka를 StatefulSet과 Local PV/PVC로 전환한다.
- Eureka와 Spring Cloud Config Server를 유지한 채 애플리케이션을 Deployment와 Service로 전환한다.
- 실제 환경변수는 Control Plane에서 직접 만든 Kubernetes Secret으로 주입한다.
- API Gateway만 NodePort로 외부에 노출한다.
- GHCR의 불변 커밋 SHA 이미지 태그로 배포하고 이전 이미지로 롤백할 수 있게 한다.
- 기동 순서, 상태 검사, 자원 상한과 장애 동작을 매니페스트에 명시한다.
- 첫 배포는 SSH와 `kubectl`로 수동 수행하고, 검증 후 GitHub Actions CD를 Kubernetes 방식으로 바꾼다.

## 3. 제외 범위

- 기존 PostgreSQL·Redis·Kafka 데이터 이전
- 다중 Control Plane, 다중 Worker, 자동 장애 조치
- EKS, AWS Load Balancer Controller, EBS CSI 동적 프로비저닝
- Ingress Controller, 도메인, TLS 인증서 자동화
- Elasticsearch, Logstash, Filebeat, Kibana, Prometheus, Grafana
- Flannel이 제공하지 않는 Kubernetes NetworkPolicy
- HPA, VPA, PodDisruptionBudget
- 첫 단계에서의 서비스별 PostgreSQL 계정 전환

로그와 메트릭 관측 스택은 크레딧이 남는 별도 서버에 구성하며 이 클러스터 전환과 독립적으로 다룬다.

## 4. 현재 구조와 전환 원칙

루트 `docker-compose.yml`은 PostgreSQL, Kafka, Redis, Discovery, Config, 여섯 비즈니스 서비스와 API Gateway를 한 Compose 네트워크에서 실행한다. GitHub Actions는 서비스별 이미지를 GHCR에 올리고 EC2의 self-hosted runner가 Compose를 갱신한다.

전환 시 애플리케이션 구조를 동시에 바꾸지 않는다.

- Eureka 기반 서비스 등록과 `lb://SERVICE-NAME` 라우팅을 유지한다.
- Spring Cloud Config native backend를 유지하고 `config/src/main/resources/configs/`를 Config 이미지에 포함한다.
- PostgreSQL 초기화 스크립트와 서비스별 Flyway baseline을 유지한다.
- Dockerfile의 서비스별 Gradle 빌드 방식을 유지한다.
- Compose의 컨테이너 이름 대신 Kubernetes Service DNS를 사용한다.
- `latest` 대신 현재 워크플로가 생성하는 짧은 Git SHA 태그를 배포 기준으로 삼는다.

## 5. 목표 토폴로지

| 서버 | Kubernetes 역할 | 배치 워크로드 |
|---|---|---|
| Medium EC2 | Control Plane + 제한된 상태 저장 워크로드 | kube-apiserver, etcd, scheduler, controller-manager, PostgreSQL, Redis |
| Large EC2 | Worker | Kafka, Discovery, Config, User, Product, Order, Payment, Settlement, Admin, API Gateway, Spring AI |
| 별도 서버 | 클러스터 외부 | 로그·메트릭 관측 스택 |

```text
외부 클라이언트
    |
    | Large Public IP:30080
    v
API Gateway NodePort
    |
    | lb://SERVICE-NAME
    v
Eureka ---- Config Server
    |             |
    +------ 비즈니스 서비스 ------+
                     |            |
                  Kafka       PostgreSQL/Redis
                  Large          Medium
```

## 6. 버전 기준

| 구성 요소 | 버전·기준 |
|---|---|
| OS | Ubuntu Server 24.04 LTS |
| Kubernetes | v1.36.2 |
| containerd | v2.3.1, CRI v1, `SystemdCgroup = true` |
| Flannel | v0.28.4, VXLAN backend |
| Flannel CNI plugin | v1.9.1 |
| Pod CIDR | `10.244.0.0/16` |
| Service CIDR | `10.96.0.0/12` |
| Namespace | `prompthub` |

Kubernetes 1.36.2는 설계일에 확인한 최신 공개 패치 버전이다. Kubernetes 1.36은 containerd 2.3.0 이상 또는 2.2.0 이상을 권장하므로 LTS인 2.3 계열의 2.3.1을 사용한다.

- [Kubernetes 1.36 릴리스](https://kubernetes.io/releases/1.36/)
- [Kubernetes container runtime 요구사항](https://kubernetes.io/docs/setup/production-environment/container-runtimes/)
- [containerd Kubernetes 호환성](https://containerd.io/releases/)
- [Flannel 공식 저장소](https://github.com/flannel-io/flannel)

## 7. 클러스터 부트스트랩과 네트워크

### 7.1 호스트 준비

두 노드에 동일하게 다음 상태를 만든다.

- swap을 영구 비활성화한다.
- `overlay`, `br_netfilter` 커널 모듈을 부팅 시 로드한다.
- `net.ipv4.ip_forward=1`을 설정한다.
- bridge 트래픽이 iptables를 통과하도록 설정한다.
- containerd와 kubelet이 모두 systemd cgroup을 사용하게 한다.
- kubeadm, kubelet, kubectl은 1.36.2로 고정하고 자동 minor 업그레이드를 막는다.
- containerd와 kubelet systemd unit을 활성화한다.

### 7.2 Control Plane과 Worker

Medium의 Private IP를 API Server advertise address와 Control Plane endpoint로 사용한다. kubeadm 설정 파일에 Kubernetes 버전, Pod CIDR, Service CIDR와 systemd cgroup을 명시하고 `kubeadm init`을 실행한다. 생성된 kubeconfig는 Medium의 운영 사용자만 읽을 수 있게 둔다.

Flannel을 설치해 CoreDNS와 노드가 Ready가 된 것을 확인한 뒤, `kubeadm init`이 발급한 토큰과 CA hash로 Large를 조인한다. 토큰이 만료되면 Medium에서 새 join command를 발급한다.

### 7.3 Flannel

Flannel은 `kube-flannel` namespace에 DaemonSet으로 설치한다. 기본 VXLAN backend와 `10.244.0.0/16`을 그대로 사용한다. 각 노드는 이 대역에서 `/24` Pod subnet을 배정받는다. 두 노드 사이 UDP 8472를 허용한다.

VPC CIDR이나 연결된 네트워크가 `10.244.0.0/16` 또는 `10.96.0.0/12`와 겹치면 클러스터를 초기화하지 않는다. 그 경우 충돌하지 않는 두 CIDR을 먼저 선택하고 kubeadm과 Flannel 설정을 동시에 바꾼다.

### 7.4 보안 그룹

| 대상 | 프로토콜·포트 | 출발지 | 목적 |
|---|---|---|---|
| 두 EC2 | TCP 22 | 관리자 고정 IP | SSH |
| Medium | TCP 6443 | Large의 보안 그룹 | Worker에서 API Server 접근 |
| 두 EC2 | TCP 10250 | 상대 노드 보안 그룹 | kubelet API |
| 두 EC2 | UDP 8472 | 상대 노드 보안 그룹 | Flannel VXLAN |
| Large | TCP 30080 | 허용할 외부 클라이언트 | API Gateway NodePort |
| 두 EC2 | 내부 통신 | 같은 클러스터 보안 그룹 | Control Plane·Pod·Service 통신 |

PostgreSQL 5432, Redis 6379, Kafka 9092·9093, Eureka 8761, Config 8888, 비즈니스 HTTP·gRPC 포트는 인터넷에 공개하지 않는다. kubectl은 Medium의 SSH 세션에서 사용하므로 6443을 공용 인터넷에 열지 않는다.

## 8. 노드 스케줄링

노드에 다음 사용자 라벨을 추가한다.

```text
Medium: prompthub.io/node-pool=control-stateful
Large:  prompthub.io/node-pool=application
```

Control Plane의 기본 `node-role.kubernetes.io/control-plane:NoSchedule` taint는 제거하지 않는다. PostgreSQL과 Redis에만 이 taint에 대한 toleration과 `control-stateful` nodeSelector를 부여한다. Kafka와 모든 애플리케이션은 `application` nodeSelector를 사용한다.

이 방식은 일반 애플리케이션이 Medium으로 유입되는 것을 막으면서 PostgreSQL과 Redis만 명시적으로 예외 처리한다. 시스템 Pod는 설치 매니페스트가 제공하는 Control Plane toleration을 사용한다.

## 9. Namespace와 Secret

모든 프로젝트 워크로드는 `prompthub` namespace에 배치한다. Flannel과 Kubernetes 시스템 구성 요소는 각 시스템 namespace를 유지한다.

실제 Secret은 Medium에 SSH로 접속해 다음 경로에 직접 작성한다.

```text
/home/ubuntu/prompthub-secrets/secret.yaml
파일 모드: 600
Git 추적: 금지
```

하나의 서버 파일 안에 여러 Secret 객체를 YAML 문서 구분자로 나눈다.

| Secret | 값 | 소비자 |
|---|---|---|
| `postgres-secret` | POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD, 서비스 역할 비밀번호 | PostgreSQL, DB 사용 서비스 |
| `runtime-secret` | DB·Kafka·Redis·Config·Eureka 주소와 포트 | 각 Spring 서비스 |
| `jwt-secret` | JWT private/public key | User, API Gateway |
| `payment-secret` | Toss secret, test mode | Payment |
| `product-secret` | AWS region, S3 bucket, AWS credential | Product |
| `spring-ai-secret` | OpenAI API key | Spring AI |
| `ghcr-pull-secret` | GHCR pull credential | GHCR 이미지를 쓰는 Pod |

Pod는 필요한 Secret key만 `secretKeyRef`로 참조한다. 실제 Secret 값이나 base64 결과는 저장소에 두지 않는다. 저장소에는 키 이름과 생성 방법만 담은 `secret.example.yaml`을 둔다. Secret 환경변수 변경 후에는 해당 Deployment 또는 StatefulSet을 재시작한다.

Kubernetes Secret은 기본 상태에서 base64 인코딩일 뿐 암호화 저장소가 아니다. 첫 학습 단계에서는 Medium의 SSH 접근과 파일 권한을 통제하고, etcd encryption at rest와 외부 Secret 관리 도구는 별도 보안 강화 범위로 둔다.

## 10. 스토리지와 상태 저장 인프라

동적 CSI 대신 static Local PV를 사용해 PV, PVC, StorageClass와 node affinity를 직접 학습한다.

### 10.1 StorageClass

`local-storage` StorageClass는 `kubernetes.io/no-provisioner`, `WaitForFirstConsumer`와 `Retain`을 사용한다. Local PV는 각각 node affinity를 가져야 한다.

### 10.2 호스트 경로와 용량

| 노드 | 컴포넌트 | 호스트 경로 | PVC 요청 |
|---|---|---|---:|
| Medium | PostgreSQL | `/var/lib/prompthub/postgres` | 20Gi |
| Medium | Redis | `/var/lib/prompthub/redis` | 5Gi |
| Large | Kafka | `/var/lib/prompthub/kafka` | 20Gi |

호스트 디렉터리는 SSH로 먼저 생성하고 컨테이너 UID/GID가 쓸 수 있게 권한을 맞춘다. PV 용량 표기는 파일시스템의 물리적 격리를 보장하지 않으므로 각 EC2 디스크의 실제 여유 공간을 별도로 점검한다.

### 10.3 PostgreSQL

- 이미지: `postgres:18.4-alpine`
- 종류: StatefulSet, replica 1
- 클라이언트 Service: `postgres:5432`
- 데이터 mount: `/var/lib/postgresql`
- probe: `pg_isready`
- 초기화 스크립트: `docker-entrypoint-initdb.d/01-init-schemas-and-roles.sh`를 ConfigMap으로 마운트

PostgreSQL 최초 기동 시 init script가 서비스별 schema와 role을 만든다. 이후 User, Product, Order, Payment, Settlement 서비스가 각 schema에 Flyway V1 baseline을 적용하고 Hibernate `ddl-auto=validate`가 결과를 검증한다. Admin은 기존 계약대로 여러 schema를 조회한다. 첫 마이그레이션에서는 현재 Config Server 계약에 맞춰 애플리케이션들이 공통 `POSTGRES_USER`와 `POSTGRES_PASSWORD`를 계속 사용한다.

### 10.4 Redis

- 이미지: `redis:7.4-alpine`
- 종류: StatefulSet, replica 1
- Service: `redis:6379`
- 데이터 mount: `/data`
- 실행 옵션: `--appendonly yes`
- probe: `redis-cli ping`

### 10.5 Kafka

- 이미지: `confluentinc/cp-kafka:7.8.0`
- 종류: StatefulSet, replica 1
- client Service: `kafka:9092`
- headless Service: `kafka-headless`
- controller port: 9093
- 데이터 mount: `/var/lib/kafka/data`
- 모드: 단일 노드 KRaft broker/controller
- probe: TCP 9092

Kafka controller quorum voter는 StatefulSet의 안정적인 DNS인 `kafka-0.kafka-headless.prompthub.svc.cluster.local:9093`을 사용하고, 애플리케이션용 advertised listener는 `kafka:9092`를 사용한다. replication factor와 minimum ISR은 단일 노드에 맞춰 1로 유지한다.

- [Kubernetes StorageClass](https://kubernetes.io/docs/concepts/storage/storage-classes/)
- [Kubernetes StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)

## 11. Spring Cloud 코어와 애플리케이션

### 11.1 워크로드와 Service

| 컴포넌트 | 종류 | replica | Service port | Pod target port | gRPC |
|---|---|---:|---:|---:|---:|
| Discovery | Deployment | 1 | 8761 | 8761 | 없음 |
| Config | Deployment | 1 | 8888 | 8888 | 없음 |
| User | Deployment | 1 | 8081 | 18081 | 9081 |
| Product | Deployment | 1 | 8082 | 18082 | 9082 |
| Order | Deployment | 1 | 8083 | 18083 | 9083 |
| Payment | Deployment | 1 | 8084 | 18084 | 없음 |
| Settlement | Deployment | 1 | 8085 | 18085 | 9085 |
| Admin | Deployment | 1 | 8086 | 18086 | 없음 |
| Spring AI | Deployment | 1 | 8087 | 18087 | 없음 |
| API Gateway | Deployment | 1 | 8000 | 8000 | 없음 |

현재 Config Server는 비즈니스 HTTP port를 18081~18086으로 설정하지만 일부 직접 호출은 `product-service:8082`처럼 808x를 사용한다. 첫 Kubernetes 전환에서는 Service의 `port`를 808x, `targetPort`를 1808x로 매핑해 두 계약을 동시에 만족한다. 이후 포트 통일은 별도 리팩터링으로 다룬다.

Spring AI 모듈은 현재 저장소에 없으므로 구현 시 다음 계약으로 추가하거나 외부 저장소 이미지를 이 계약에 맞춘다.

- Kubernetes 이름: `spring-ai-service`
- Eureka application name: `SPRING-AI-SERVICE`
- GHCR 이미지: `ghcr.io/prgrms-be-adv-devcourse/prompthub-spring-ai-service:<git-sha>`
- Service port 8087, Pod target port 18087
- Actuator health endpoint 제공
- `OPENAI_API_KEY`는 `spring-ai-secret`에서 주입

### 11.2 API Gateway 외부 진입

API Gateway Service는 `type: NodePort`, `port: 8000`, `targetPort: 8000`, `nodePort: 30080`과 `externalTrafficPolicy: Local`을 사용한다. Gateway Pod는 Large에만 배치하고 Large 보안 그룹에서만 30080을 외부에 연다.

Ingress, 도메인과 HTTPS는 서비스 배포가 안정화된 뒤 별도 단계에서 추가한다.

## 12. 설정 흐름과 기동 순서

### 12.1 런타임 주소

```text
CONFIG_IMPORT=configserver:http://config:8888
EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://discovery:8761/eureka/
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
REDIS_HOST=redis
REDIS_PORT=6379
```

Config Server는 native backend 설정을 이미지 안에서 제공한다. Config 설정을 바꾸면 Config 이미지를 새 SHA로 빌드·배포한 뒤 영향을 받는 서비스를 재시작한다. Config Server 연결은 optional이 아닌 필수 import로 지정한다. 기존 Spring Cloud Config retry 6회를 소진해도 연결할 수 없으면 애플리케이션이 종료되고 Kubernetes가 다시 시작한다.

### 12.2 배포 파동

```text
1. Namespace, Secret, StorageClass, PV, PVC
2. PostgreSQL, Redis, Kafka
3. Discovery
4. Config Server
5. 여섯 비즈니스 서비스와 Spring AI
6. API Gateway
7. NodePort 스모크 테스트
```

각 파동은 이전 파동의 readiness를 `kubectl wait`와 `kubectl rollout status`로 확인한 뒤 진행한다.

### 12.3 Init Container

Kubernetes에는 Compose `depends_on`이 없으므로 애플리케이션 Pod의 init container가 필수 인프라 endpoint를 기다린다.

- 모든 Spring 애플리케이션: Config와 Discovery
- DB 사용 서비스: PostgreSQL
- User: Redis
- Kafka 사용 서비스: Kafka
- Gateway: Config와 Discovery

비즈니스 서비스끼리는 init dependency를 만들지 않는다. 서비스 간 연결은 Eureka, gRPC deadline, retry와 circuit breaker로 처리해 상호 대기와 기동 교착을 막는다.

## 13. Probe와 종료

Spring Boot 서비스는 다음 Actuator probe를 사용한다.

| Probe | 경로 | 초기 정책 | 목적 |
|---|---|---|---|
| startup | `/actuator/health` | 10초 간격, 30회 실패 허용 | 최대 5분 기동 대기 |
| readiness | `/actuator/health/readiness` | 10초 간격, 연속 3회 실패 | Service endpoint 제외 |
| liveness | `/actuator/health/liveness` | 20초 간격, 연속 3회 실패 | 교착 컨테이너 재시작 |

Actuator probe endpoint를 명시적으로 활성화하고 `eureka.client.healthcheck.enabled=true`로 애플리케이션 health 상태를 Eureka에도 전달한다. PostgreSQL, Redis와 Kafka는 10절의 전용 probe를 사용한다.

Spring 서비스는 graceful shutdown을 활성화하고 `terminationGracePeriodSeconds: 30`을 사용한다. PostgreSQL과 Kafka는 60초를 사용한다. Stateless Deployment는 `maxSurge: 1`, `maxUnavailable: 0`으로 한 서비스씩 교체한다. 추가 Pod가 Pending이 되면 기존 Pod를 유지한 채 자원값을 조정한다.

- [Kubernetes probe](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Kubernetes init container](https://kubernetes.io/docs/concepts/workloads/pods/init-containers/)

## 14. 초기 자원 기준

아래 값은 Medium 2 vCPU·4GiB, Large 2 vCPU·8GiB급을 가정한 첫 배포 기준이다. 확정 용량이 아니다. 실제 API·배치 부하를 실행한 뒤 OOMKilled, CPU throttling, Pending, eviction과 실제 사용량을 근거로 서비스별로 조정한다.

### 14.1 Medium

| Pod | CPU request / limit | Memory request / limit |
|---|---:|---:|
| PostgreSQL | 300m / 800m | 512Mi / 1Gi |
| Redis | 50m / 250m | 128Mi / 256Mi |

Control Plane과 OS를 위해 최소 1~1.5GiB 여유를 유지한다. PostgreSQL 증설이 이 여유를 침범하면 개별 limit만 계속 높이지 않고 인스턴스 확장이나 워크로드 재배치를 선택한다.

### 14.2 Large

| Pod 그룹 | 수 | Pod당 CPU request / limit | Pod당 Memory request / limit |
|---|---:|---:|---:|
| Kafka | 1 | 250m / 700m | 768Mi / 1280Mi |
| Discovery·Config | 2 | 100m / 300m | 256Mi / 512Mi |
| 비즈니스 서비스 | 6 | 100m / 500m | 256Mi / 512Mi |
| API Gateway | 1 | 100m / 500m | 256Mi / 512Mi |
| Spring AI | 1 | 150m / 600m | 384Mi / 768Mi |

Spring JVM은 컨테이너 limit를 인식하도록 `JAVA_TOOL_OPTIONS`에서 heap 비율을 제한한다. Kafka는 `KAFKA_HEAP_OPTS`로 heap 상한을 둔다. 첫 단계에는 Metrics Server를 필수 설치하지 않는다. 외부 관측 스택이나 이후 추가한 Metrics Server의 사용량 자료로 조정하되, 변경값은 항상 매니페스트에 반영한다.

- [Kubernetes resource management](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/)

## 15. 이미지와 배포

애플리케이션 이미지는 기존 `reusable-docker-build.yml`이 생성하는 짧은 Git SHA 태그를 사용한다. `latest`는 매니페스트에 사용하지 않는다. GHCR이 private이면 Medium에서 image pull Secret을 만들어 namespace의 Pod가 참조하게 한다.

첫 배포는 다음 흐름으로 수동 수행한다.

```text
GitHub Actions 이미지 빌드·push
    -> Medium SSH
    -> Secret apply
    -> Kustomize overlay apply
    -> rollout status
    -> Eureka/Config/API 검증
```

잘못된 stateless 배포는 `kubectl rollout undo` 또는 직전 SHA 태그로 되돌린다. StatefulSet 이미지는 명시적 이전 태그로 복구한다. Secret 변경 후에는 영향받는 워크로드만 재시작한다.

수동 절차가 재현 가능하게 검증된 후 현재 Compose CD의 deploy job을 다음 방향으로 교체한다.

- 변경 서비스 이미지를 GHCR에 SHA 태그로 push한다.
- self-hosted runner가 kubeconfig로 해당 Deployment image를 갱신한다.
- `kubectl rollout status` 실패 시 job을 실패 처리한다.
- Config 이미지가 바뀌면 Config rollout 후 영향 서비스를 순차 재시작한다.
- PostgreSQL·Redis·Kafka는 이미지나 매니페스트가 바뀐 경우에만 갱신한다.

## 16. 장애 동작

| 장애 | 예상 동작과 대응 |
|---|---|
| 애플리케이션 프로세스 종료 | Deployment가 Pod를 재생성한다. 반복 시 logs, events와 last state를 확인한다. |
| startup probe 실패 | 준비될 때까지 liveness를 보류한다. 5분을 넘기면 Config·DB·Kafka 연결을 확인한다. |
| readiness 실패 | Kubernetes Service endpoint에서 제외한다. Eureka health 상태도 DOWN으로 전달한다. |
| liveness 실패 | kubelet이 컨테이너를 재시작한다. |
| OOMKilled | 실제 peak와 JVM heap을 확인해 leak 수정 또는 limit 조정을 선택한다. |
| Config 중단 | 기존 Pod는 로드한 설정으로 계속 실행하며 신규 Pod는 Config 복구 전 기동하지 못한다. |
| Eureka 중단 | 신규 등록과 조회가 실패하고 Gateway 라우팅이 불안정해진다. Discovery를 먼저 복구한다. |
| Medium 중단 | Control Plane, PostgreSQL, Redis가 동시에 중단된다. Worker의 기존 Pod는 남을 수 있으나 관리와 핵심 요청은 실패한다. |
| Large 중단 | Kafka와 모든 애플리케이션이 중단된다. Medium의 Control Plane과 상태 저장 Pod만 남는다. |
| Local PV 노드 소실 | 다른 노드로 자동 이동하지 않으며 데이터는 복구하지 않는다. 새 PV로 초기화한다. |
| 잘못된 이미지 | 직전 SHA 태그로 rollback한다. |

containerd와 kubelet은 부팅 시 자동 시작한다. 노드 재부팅 후 static Control Plane Pod와 관리 워크로드가 다시 올라오는지를 복구 훈련에 포함한다.

## 17. 보안 기준

- SSH는 관리자 고정 IP에서만 허용한다.
- API Server 6443은 인터넷에 공개하지 않는다.
- API Gateway NodePort 외에는 public inbound를 허용하지 않는다.
- 실제 Secret YAML과 kubeconfig는 Git에 커밋하지 않는다.
- 애플리케이션 ServiceAccount의 token 자동 mount는 필요하지 않으면 끈다.
- 각 Pod에는 필요한 Secret key만 주입한다.
- GHCR credential은 namespace Secret으로 관리한다.
- Flannel 선택으로 NetworkPolicy 격리는 제공하지 않음을 수용하고 EC2 보안 그룹과 ClusterIP 경계를 사용한다.
- 현재 Docker 이미지의 실행 사용자는 첫 이전에서 유지하며 non-root 전환은 애플리케이션 이미지 하드닝 작업으로 분리한다.

## 18. 저장소 구조

```text
k8s/
├── base/
│   ├── namespace.yaml
│   ├── storage/
│   ├── infrastructure/
│   │   ├── postgres/
│   │   ├── redis/
│   │   └── kafka/
│   ├── platform/
│   │   ├── discovery/
│   │   └── config/
│   ├── services/
│   │   ├── user/
│   │   ├── product/
│   │   ├── order/
│   │   ├── payment/
│   │   ├── settlement/
│   │   ├── admin/
│   │   └── spring-ai/
│   └── gateway/
├── overlays/
│   └── learning/
│       └── kustomization.yaml
├── templates/
│   └── secret.example.yaml
└── README.md
```

호스트 설치 명령과 검증 절차는 `docs/guides/kubeadm-cluster-bootstrap.md`에 둔다. 실제 Secret 파일은 이 구조 밖의 Medium 로컬 경로에만 둔다.

## 19. 검증과 완료 조건

### 19.1 클러스터

- Medium과 Large가 `Ready`다.
- kube-system, kube-flannel, CoreDNS Pod가 Running·Ready다.
- 서로 다른 노드에 배치한 테스트 Pod가 Service DNS와 Pod 네트워크로 통신한다.
- 외부에서 API Server·내부 인프라 포트에 접근할 수 없다.

### 19.2 스토리지·인프라

- Local PV와 PVC가 의도한 노드에서 Bound다.
- PostgreSQL, Redis, Kafka StatefulSet이 Ready 1/1이다.
- PostgreSQL init script가 schema와 role을 만들고 서비스별 Flyway V1이 성공한다.
- PostgreSQL·Redis Pod를 삭제해도 같은 PV를 붙여 다시 기동한다.
- Kafka에 테스트 topic 생성·생산·소비가 성공한다.

### 19.3 플랫폼·애플리케이션

- Discovery와 Config가 Ready 1/1이다.
- Config endpoint가 서비스별 설정을 반환한다.
- User, Product, Order, Payment, Settlement, Admin, API Gateway와 Spring AI가 Eureka에 등록된다.
- API Gateway NodePort를 통한 회원가입, 로그인과 상품 조회 스모크 테스트가 성공한다.
- HTTP 직접 호출과 User·Product·Order·Settlement gRPC 통신이 성공한다.
- 서비스 Pod를 삭제하면 자동 재생성되고 다시 Eureka에 등록된다.

### 19.4 배포·복구

- 짧은 SHA 태그 이미지로 서비스 하나를 갱신하고 rollout status가 성공한다.
- 의도적으로 잘못된 이미지 태그를 적용한 뒤 직전 SHA로 복구한다.
- Medium과 Large를 한 대씩 재부팅해 설계된 장애 영향과 복구 순서를 확인한다.

## 20. 구현 계획 분할

이 설계는 하나의 이슈로 추적하지만 실행 계획은 독립적으로 검증 가능한 네 묶음으로 나눈다.

1. 클러스터 부트스트랩: EC2 사전 점검, containerd, kubeadm, Flannel, 노드 join과 네트워크 검증
2. 상태 저장 인프라: Local PV/PVC, PostgreSQL init/Flyway, Redis, Kafka와 재생성 검증
3. 플랫폼·애플리케이션: Secret, Discovery, Config, 비즈니스 서비스, Spring AI, Gateway와 HTTP/gRPC 검증
4. 배포 전환: SHA image rollout, rollback, Config 변경 절차와 GitHub Actions CD 교체

각 묶음은 앞 단계의 검증 결과를 입력으로 받고 자체 완료 조건을 가진다. 클러스터가 Ready가 아니면 인프라 배포로 넘어가지 않고, 인프라가 Ready가 아니면 애플리케이션 배포로 넘어가지 않는다.

## 21. 채택한 절충점

- kubeadm 직접 설치를 선택해 학습 범위를 넓히는 대신 관리형 제어면의 안정성을 포기한다.
- Flannel을 선택해 설치와 네트워크 이해를 단순화하는 대신 NetworkPolicy를 포기한다.
- Control Plane에 PostgreSQL과 Redis를 제한 배치해 두 서버 자원을 활용하는 대신 Medium 장애 범위를 키운다.
- Local PV를 선택해 CSI 복잡도를 피하는 대신 노드 간 이동과 자동 복구를 포기한다.
- Eureka와 Config를 유지해 애플리케이션 변경을 줄이는 대신 Kubernetes의 native service discovery·ConfigMap으로 즉시 전환하지 않는다.
- NodePort를 선택해 외부 진입을 단순화하는 대신 표준 80/443, 도메인과 TLS를 후속 단계로 미룬다.
- 초기 자원 requests/limits를 보수적으로 지정하지만 실제 부하 관측을 통해 반드시 재조정한다.
