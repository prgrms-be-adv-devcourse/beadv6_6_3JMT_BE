# Kubernetes 아키텍처 명세

> 상태: 승인된 목표 아키텍처, 매니페스트 구현 전
> 관련 이슈: [#340](https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/issues/340)
> 전환 설계: [kubeadm 기반 Kubernetes 배포 전환 설계](./2026-07-14-kubeadm-kubernetes-migration-design.md)
> 최종 갱신: 2026-07-14

## 1. 문서 목적

이 문서는 PromptHub 백엔드를 Kubernetes에 배포할 때 따라야 하는 목표 아키텍처와 매니페스트 구성 규칙을 정의한다. 날짜가 붙은 전환 설계 문서는 선택 이유와 구현 순서를 설명하고, 이 문서는 실제 Kubernetes 리소스가 따라야 하는 지속적인 기준을 제공한다.

이 문서에서 다루는 범위는 다음과 같다.

- 두 EC2 노드의 역할과 워크로드 배치
- Kubernetes 리소스 종류, 이름, 포트와 의존 관계
- Flannel, Service DNS, Eureka와 Config Server의 역할 분담
- Local PV/PVC와 상태 저장 워크로드
- Secret과 런타임 설정 주입 방식
- `k8s/` 매니페스트 디렉터리와 Kustomize 패키지 구성
- 리소스 label, 파일 이름, 이미지 태그와 변경 규칙
- 기동, 검증, 장애와 복구의 기본 계약

애플리케이션의 Java 패키지 구조, 도메인 설계, 로그·메트릭 스택은 이 문서의 범위가 아니다.

## 2. 상태와 기준 우선순위

이 문서는 목표 상태 명세다. 현재 저장소에는 아직 `k8s/` 디렉터리와 Kubernetes 매니페스트가 없다. 구현할 매니페스트는 이 문서의 구조와 계약을 따라야 한다.

서로 다른 문서나 설정이 충돌하면 다음 순서로 판단한다.

1. 실제 애플리케이션 코드가 제공하거나 소비하는 프로토콜 계약
2. 이 문서의 Kubernetes 리소스·배치 계약
3. 날짜가 붙은 전환 설계의 의사결정과 초기 추정값
4. 기존 `docker-compose.yml`의 호스트 포트와 기동 계약

충돌을 발견하면 매니페스트에서 임의로 우회하지 않는다. 코드·Config Server·이 문서를 함께 정렬하고 변경 근거를 이슈에 남긴다.

## 3. 설계 원칙과 경계

- Kubernetes는 kubeadm으로 직접 설치한다. EKS나 k3s는 사용하지 않는다.
- 클러스터는 단일 Control Plane과 단일 Worker로 구성한다.
- Pod 네트워크는 Flannel VXLAN을 사용한다.
- 모든 프로젝트 워크로드의 replica는 첫 배포에서 1개다.
- PostgreSQL, Redis와 Kafka는 StatefulSet과 static Local PV/PVC를 사용한다.
- Discovery와 Config Server는 유지한다. Kubernetes DNS나 ConfigMap으로 즉시 대체하지 않는다.
- 실제 환경변수는 Medium에서 직접 관리하는 Secret YAML로 주입한다.
- 외부 진입점은 Large의 API Gateway NodePort 하나다.
- 애플리케이션 이미지는 `latest`가 아닌 불변 Git SHA 태그를 사용한다.
- 매니페스트는 Kustomize base와 `learning` overlay로 관리한다.
- 로그와 메트릭 수집 시스템은 별도 서버에 두며 이 클러스터에 배치하지 않는다.
- 이 구성은 학습 환경이다. 노드 장애 고가용성이나 데이터 자동 복구를 제공하지 않는다.

## 4. 물리 토폴로지

| 서버 | Kubernetes 역할 | 사용자 label | 배치 대상 |
|---|---|---|---|
| Medium EC2 | Control Plane | `prompthub.io/node-pool=control-stateful` | Control Plane, PostgreSQL, Redis |
| Large EC2 | Worker | `prompthub.io/node-pool=application` | Kafka, Discovery, Config, 전체 애플리케이션 |
| 별도 서버 | 클러스터 외부 | 해당 없음 | 로그·메트릭 관측 스택 |

```mermaid
flowchart TB
    Client([외부 클라이언트])

    subgraph Cluster["kubeadm Kubernetes Cluster"]
        subgraph Medium["Medium EC2 · Control Plane"]
            CP["API Server · etcd · Scheduler · Controller Manager"]
            PG[(PostgreSQL)]
            Redis[(Redis)]
        end

        subgraph Large["Large EC2 · Worker"]
            GW["API Gateway · NodePort 30080"]
            Eureka["Eureka Discovery"]
            Config["Spring Cloud Config"]
            Apps["User · Product · Order · Payment · Settlement · Admin · Spring AI"]
            Kafka[(Kafka)]
        end

        Flannel["Flannel VXLAN · Pod CIDR 10.244.0.0/16"]
    end

    Observability["별도 관측 서버"]

    Client -->|"TCP 30080"| GW
    GW -->|"lb://SERVICE-NAME"| Eureka
    GW --> Apps
    Apps -->|"기동 시 설정 조회"| Config
    Apps --> PG
    Apps --> Redis
    Apps --> Kafka
    CP --- Flannel
    Apps --- Flannel
    Apps -.->|로그·메트릭 전송은 별도 설계| Observability
```

### 4.1 장애 경계

| 장애 지점 | 영향 |
|---|---|
| Medium 중단 | Control Plane, PostgreSQL과 Redis가 동시에 중단된다. 기존 Worker Pod가 남아도 관리와 핵심 요청이 실패한다. |
| Large 중단 | Kafka, Discovery, Config와 모든 애플리케이션이 중단된다. |
| Local PV가 있는 노드 소실 | 다른 노드로 볼륨이나 StatefulSet이 자동 이동하지 않는다. |
| Config 중단 | 실행 중인 Pod는 기존 설정으로 동작하지만 신규 Pod는 정상 기동하지 못한다. |
| Eureka 중단 | 신규 등록과 조회가 실패하고 Gateway 라우팅이 불안정해진다. |

## 5. 클러스터 기준

| 항목 | 기준 |
|---|---|
| OS | Ubuntu Server 24.04 LTS |
| Kubernetes | v1.36.2 |
| containerd | v2.3.1, CRI v1, systemd cgroup |
| Flannel | v0.28.4, VXLAN |
| Flannel CNI plugin | v1.9.1 |
| Pod CIDR | `10.244.0.0/16` |
| Service CIDR | `10.96.0.0/12` |
| 프로젝트 Namespace | `prompthub` |
| 외부 NodePort | `30080` |

버전 변경은 한 구성 요소만 독립적으로 올리지 않는다. Kubernetes, containerd, Flannel 호환성을 확인하고 kubeadm 설치 문서와 이 표를 같은 변경에서 갱신한다.

## 6. Namespace와 스케줄링

### 6.1 Namespace

- 프로젝트 리소스는 `prompthub` namespace에 둔다.
- `StorageClass`와 `PersistentVolume`은 cluster-scoped 리소스이므로 namespace를 지정하지 않는다.
- Flannel은 `kube-flannel`, Kubernetes 시스템 Pod는 `kube-system`을 유지한다.
- 다른 환경이 추가되기 전까지 namespace를 서비스별로 나누지 않는다.

### 6.2 Node 배치

Control Plane의 기본 `node-role.kubernetes.io/control-plane:NoSchedule` taint는 유지한다.

| 워크로드 | `nodeSelector` | Control Plane toleration |
|---|---|---|
| PostgreSQL, Redis | `prompthub.io/node-pool=control-stateful` | 필요 |
| Kafka | `prompthub.io/node-pool=application` | 없음 |
| Discovery, Config | `prompthub.io/node-pool=application` | 없음 |
| 비즈니스 서비스, Spring AI, Gateway | `prompthub.io/node-pool=application` | 없음 |

PostgreSQL과 Redis 이외의 프로젝트 Pod에는 Control Plane toleration을 추가하지 않는다. Local PV의 `nodeAffinity`도 같은 사용자 label을 기준으로 고정한다.

## 7. 논리 아키텍처와 통신 책임

Kubernetes 도입 후에도 DNS, Eureka와 Config Server의 책임을 구분한다.

| 기능 | 담당 | 사용 예 |
|---|---|---|
| Pod 간 기본 주소 확인 | Kubernetes Service DNS | `postgres:5432`, `kafka:9092`, `config:8888` |
| HTTP 서비스 등록·조회 | Eureka | Gateway의 `lb://USER-SERVICE` |
| 애플리케이션 설정 제공 | Spring Cloud Config | `configserver:http://config:8888` |
| 민감값과 런타임 환경변수 | Kubernetes Secret | DB 비밀번호, JWT key, 외부 API key |
| 비동기 이벤트 | Kafka | `kafka:9092` |
| 내부 gRPC | Kubernetes Service DNS | `user-service:9081` |

Kubernetes Service 이름은 런타임 주소 계약이다. 이름을 바꾸면 Config Server 파일, Secret key 또는 애플리케이션 설정도 함께 바꿔야 한다.

### 7.1 외부 요청 흐름

```mermaid
sequenceDiagram
    participant C as Client
    participant NP as Large:30080
    participant GW as API Gateway:8000
    participant E as Eureka:8761
    participant S as Business Service
    participant DB as PostgreSQL / Redis / Kafka

    C->>NP: HTTP 요청
    NP->>GW: NodePort 전달
    GW->>E: SERVICE-NAME 인스턴스 조회
    E-->>GW: Ready 인스턴스 주소
    GW->>S: 인증 헤더를 포함한 요청 전달
    S->>DB: 데이터 조회·저장 또는 이벤트 처리
    S-->>C: 응답
```

### 7.2 외부 egress

| 출발 워크로드 | 목적지 | 포트 | 용도 |
|---|---|---:|---|
| Payment | Toss Payments API | 443 | 결제 승인·취소 |
| Product | AWS S3 API | 443 | 상품 파일 저장 |
| Spring AI | OpenAI API | 443 | AI 요청 |
| 각 노드 | GHCR와 패키지 저장소 | 443 | 이미지 pull과 설치 |

외부 egress를 차단하는 보안 구성을 추가할 때는 이 통신을 명시적으로 허용한다.

## 8. Kubernetes 리소스 명세

### 8.1 상태 저장 인프라

| 이름 | Kind | 노드 | Service | 포트 | 영속성 |
|---|---|---|---|---:|---|
| `postgres` | StatefulSet | Medium | `postgres`, `postgres-headless` | 5432 | `postgres-data` PVC 20Gi |
| `redis` | StatefulSet | Medium | `redis`, `redis-headless` | 6379 | `redis-data` PVC 5Gi |
| `kafka` | StatefulSet | Large | `kafka`, `kafka-headless` | 9092, 9093 | `kafka-data` PVC 20Gi |

각 StatefulSet은 Pod network identity를 위한 headless Service와 클라이언트용 ClusterIP Service를 분리한다. Kafka controller identity에는 `kafka-headless`를 사용하고 애플리케이션은 `kafka:9092`로 접속한다.

### 8.2 플랫폼과 애플리케이션

| Kubernetes 이름 | Kind | replica | Service 포트 → Pod 포트 | Eureka 이름 | 상태 |
|---|---|---:|---|---|---|
| `discovery` | Deployment | 1 | 8761 → 8761 | 등록하지 않음 | 기존 모듈 |
| `config` | Deployment | 1 | 8888 → 8888 | 등록하지 않음 | 기존 모듈 |
| `user-service` | Deployment | 1 | HTTP 8081 → 18081, gRPC 9081 → 9081 | `USER-SERVICE` | 기존 모듈 |
| `product-service` | Deployment | 1 | HTTP 8082 → 18082, gRPC 9082 → 9082 | `PRODUCT-SERVICE` | 기존 모듈 |
| `order-service` | Deployment | 1 | HTTP 8083 → 18083, gRPC 9083 → 9083 | `ORDER-SERVICE` | 기존 모듈 |
| `payment-service` | Deployment | 1 | HTTP 8084 → 18084, gRPC 9084 → 9084 | `PAYMENT-SERVICE` | 기존 gRPC 서버 포함 |
| `settlement-service` | Deployment | 1 | HTTP 8085 → 18085 | `SETTLEMENT-SERVICE` | 현재 gRPC 클라이언트만 사용 |
| `admin-service` | Deployment | 1 | HTTP 8086 → 18086 | `ADMIN-SERVICE` | 기존 모듈 |
| `spring-ai-service` | Deployment | 1 | HTTP 8087 → 18087 | `SPRING-AI-SERVICE` | 구현·이미지 준비 필요 |
| `apigateway` | Deployment | 1 | 8000 → 8000 | `APIGATEWAY` | 기존 모듈 |

HTTP Service의 808x 포트는 기존 내부 호출 계약을 유지하고, 1808x `targetPort`는 Config Server의 현재 `server.port`를 따른다.

Payment는 `PaymentQueryGrpcService` 구현과 gRPC server starter를 가지므로 9084를 Service로 노출한다. Kubernetes 적용 전에 `config/src/main/resources/configs/payment-service.yml`에서 `spring.grpc.server.port`를 9084로 명시해야 한다. Settlement의 Compose 9085 매핑과 Config 값은 현재 서버 구현이 없는 레거시 설정이므로 Kubernetes Service에는 9085를 노출하지 않는다.

Spring AI는 현재 저장소에 모듈이 없다. 다음 조건을 충족한 이미지가 준비되기 전에는 해당 패키지를 base의 최종 `kustomization.yaml`에 포함하지 않는다.

- 이미지: `ghcr.io/prgrms-be-adv-devcourse/prompthub-spring-ai-service:<git-sha>`
- `SPRING-AI-SERVICE`로 Eureka 등록
- HTTP 18087과 Actuator health endpoint 제공
- `OPENAI_API_KEY`를 `spring-ai-secret`에서 주입

### 8.3 Gateway 외부 노출

`apigateway` Service는 다음 계약을 고정한다.

| 필드 | 값 |
|---|---|
| type | `NodePort` |
| port / targetPort | 8000 / 8000 |
| nodePort | 30080 |
| externalTrafficPolicy | `Local` |
| 배치 노드 | Large |

Large 보안 그룹에서 30080만 허용된 외부 클라이언트에 연다. Discovery, Config, 인프라와 비즈니스 Service는 모두 ClusterIP로 유지한다.

## 9. 네트워크 명세

### 9.1 Flannel

- backend는 VXLAN이다.
- Pod CIDR은 `10.244.0.0/16`이다.
- 노드 사이 UDP 8472를 허용한다.
- VPC나 연결 네트워크와 Pod·Service CIDR이 겹치면 kubeadm 초기화 전에 CIDR을 다시 정한다.
- Flannel은 NetworkPolicy를 집행하지 않는다. 첫 단계의 격리는 보안 그룹, NodePort 최소화와 ClusterIP 경계에 의존한다.

### 9.2 보안 그룹

| 대상 | 포트 | 출발지 |
|---|---|---|
| 두 EC2 | TCP 22 | 관리자 고정 IP |
| Medium | TCP 6443 | Large 보안 그룹 |
| 두 EC2 | TCP 10250 | 상대 노드 보안 그룹 |
| 두 EC2 | UDP 8472 | 상대 노드 보안 그룹 |
| Large | TCP 30080 | 허용된 외부 클라이언트 |

PostgreSQL 5432, Redis 6379, Kafka 9092·9093, Eureka 8761, Config 8888, 내부 HTTP·gRPC 포트는 인터넷에 공개하지 않는다.

## 10. 설정과 Secret 명세

### 10.1 설정 책임

| 데이터 | 저장 위치 | Git 추적 |
|---|---|---|
| 공통·서비스별 Spring 설정 | Config 이미지의 `config/src/main/resources/configs/` | 허용 |
| 실제 비밀번호·key·credential | Medium의 `/home/ubuntu/prompthub-secrets/secret.yaml` | 금지 |
| Secret key 이름과 작성 예시 | `k8s/templates/secret.example.yaml` | 허용 |
| PostgreSQL 초기화 스크립트 | ConfigMap으로 마운트할 선언적 매니페스트 | 허용 |
| GHCR pull credential | `ghcr-pull-secret` | 금지 |

사용자 결정에 따라 호스트·포트처럼 민감하지 않은 런타임 환경변수도 `runtime-secret`에서 주입한다. 일반 애플리케이션 설정을 별도 ConfigMap으로 이중 관리하지 않는다.

### 10.2 Secret 객체

| Secret | 주요 key | 소비자 |
|---|---|---|
| `postgres-secret` | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, 역할 비밀번호 | PostgreSQL, DB 사용 서비스 |
| `runtime-secret` | DB·Redis·Kafka·Config·Eureka 주소와 포트 | Spring 워크로드 |
| `jwt-secret` | `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` | User, Gateway |
| `payment-secret` | `TOSS_SECRET_KEY`, `TOSS_TEST_MODE` | Payment |
| `product-secret` | AWS region, S3 bucket, AWS credential | Product |
| `spring-ai-secret` | `OPENAI_API_KEY` | Spring AI |
| `ghcr-pull-secret` | Docker registry credential | GHCR 이미지를 쓰는 Pod |

실제 Secret 파일은 mode 600으로 유지한다. 각 Pod는 필요한 key만 `secretKeyRef`로 참조하고 ServiceAccount token은 Kubernetes API 사용이 없으면 자동 mount하지 않는다.

### 10.3 런타임 주소

```text
SPRING_CONFIG_IMPORT=configserver:http://config:8888
EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://discovery:8761/eureka/
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
REDIS_HOST=redis
REDIS_PORT=6379
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
```

Config 파일은 Config Server 이미지에 포함된다. 설정을 바꾸면 Config 이미지를 새 SHA로 배포하고 영향을 받는 Deployment를 순차 재시작한다.

## 11. 스토리지 명세

### 11.1 StorageClass

`local-storage`는 다음 값을 사용한다.

```yaml
provisioner: kubernetes.io/no-provisioner
volumeBindingMode: WaitForFirstConsumer
reclaimPolicy: Retain
```

### 11.2 PV/PVC

| PV | PVC | 노드 | `spec.local.path` | 컨테이너 mount | 용량 | access mode |
|---|---|---|---|---|---:|---|
| `postgres-local-pv` | `postgres-data` | Medium | `/var/lib/prompthub/postgres` | `/var/lib/postgresql` | 20Gi | `ReadWriteOnce` |
| `redis-local-pv` | `redis-data` | Medium | `/var/lib/prompthub/redis` | `/data` | 5Gi | `ReadWriteOnce` |
| `kafka-local-pv` | `kafka-data` | Large | `/var/lib/prompthub/kafka` | `/var/lib/kafka/data` | 20Gi | `ReadWriteOnce` |

PV는 사용자 node-pool label을 이용한 `nodeAffinity`를 가져야 한다. 호스트 디렉터리는 매니페스트 적용 전에 SSH로 만들고 컨테이너 UID/GID가 쓸 수 있게 설정한다.

PV 용량은 EC2 파일시스템을 물리적으로 분리하거나 강제 제한하지 않는다. 디스크 사용량은 별도로 확인해야 한다. 데이터는 이전하지 않으며 초기 배포에서 새로 생성한다.

## 12. 매니페스트 패키지 구조

목표 디렉터리는 다음과 같다.

```text
k8s/
├── base/
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── storage/
│   │   ├── kustomization.yaml
│   │   ├── storage-class.yaml
│   │   ├── postgres-pv.yaml
│   │   ├── postgres-pvc.yaml
│   │   ├── redis-pv.yaml
│   │   ├── redis-pvc.yaml
│   │   ├── kafka-pv.yaml
│   │   └── kafka-pvc.yaml
│   ├── infrastructure/
│   │   ├── kustomization.yaml
│   │   ├── postgres/
│   │   │   ├── kustomization.yaml
│   │   │   ├── init-configmap.yaml
│   │   │   ├── service.yaml
│   │   │   ├── headless-service.yaml
│   │   │   └── statefulset.yaml
│   │   ├── redis/
│   │   │   ├── kustomization.yaml
│   │   │   ├── service.yaml
│   │   │   ├── headless-service.yaml
│   │   │   └── statefulset.yaml
│   │   └── kafka/
│   │       ├── kustomization.yaml
│   │       ├── service.yaml
│   │       ├── headless-service.yaml
│   │       └── statefulset.yaml
│   ├── platform/
│   │   ├── kustomization.yaml
│   │   ├── discovery/
│   │   │   ├── kustomization.yaml
│   │   │   ├── deployment.yaml
│   │   │   └── service.yaml
│   │   └── config/
│   │       ├── kustomization.yaml
│   │       ├── deployment.yaml
│   │       └── service.yaml
│   ├── services/
│   │   ├── kustomization.yaml
│   │   ├── user/
│   │   ├── product/
│   │   ├── order/
│   │   ├── payment/
│   │   ├── settlement/
│   │   ├── admin/
│   │   └── spring-ai/
│   └── gateway/
│       ├── kustomization.yaml
│       ├── deployment.yaml
│       └── service.yaml
├── overlays/
│   └── learning/
│       ├── kustomization.yaml
│       └── patches/
│           ├── resource-limits.yaml
│           └── gateway-node-port.yaml
├── templates/
│   └── secret.example.yaml
└── README.md
```

`services` 아래 각 워크로드 디렉터리는 기본적으로 다음 세 파일을 가진다.

```text
<service>/
├── kustomization.yaml
├── deployment.yaml
└── service.yaml
```

Spring AI 패키지는 계약을 문서화하기 위해 디렉터리를 예약하지만, 이미지와 모듈 준비 전에는 상위 `services/kustomization.yaml`의 `resources`에 넣지 않는다.

PostgreSQL의 `init-configmap.yaml`은 루트 `docker-entrypoint-initdb.d/01-init-schemas-and-roles.sh`와 같은 내용을 사용한다. 초기화 스크립트를 바꾸면 두 배포 방식의 입력이 달라지지 않도록 ConfigMap 매니페스트도 같은 커밋에서 갱신하고 차이를 검사한다.

## 13. Kustomize 구성 규칙

### 13.1 Base

`base`는 환경과 무관한 다음 계약을 가진다.

- 리소스 이름, Service 포트와 selector
- workload kind와 replica 기본값
- probe, 종료 유예와 배포 전략
- nodeSelector와 필요한 toleration
- PVC 이름과 mount path
- 초기 requests/limits
- Secret key 참조 이름

Base 이미지도 `latest`를 사용하지 않는다. 첫 구현 시 동작이 검증된 SHA 태그를 기록하고 overlay가 배포할 SHA로 교체한다.

애플리케이션 이미지 이름은 다음 규칙을 사용한다.

```text
ghcr.io/prgrms-be-adv-devcourse/prompthub-<module-name>:<short-git-sha>
```

`<module-name>`은 저장소의 Gradle 모듈 이름을 사용한다. 예외로 Spring AI는 8.2절에 정의한 예약 이름을 사용한다.

### 13.2 Learning overlay

`overlays/learning`은 두 EC2 학습 환경의 실제 배포값을 가진다.

- GHCR 이미지 이름과 Git SHA 태그
- 실제 운영 중 조정한 requests/limits patch
- Gateway NodePort 설정
- `app.kubernetes.io/instance=learning` 환경 label
- 환경별 replica 변경이 생긴 경우의 patch

실제 Secret 리소스는 overlay에 포함하지 않는다. Medium에서 `kubectl apply -f /home/ubuntu/prompthub-secrets/secret.yaml`로 먼저 적용한다.

### 13.3 적용 단위

각 상위 디렉터리는 자체 `kustomization.yaml`을 가져 독립적으로 렌더링할 수 있어야 한다.

| 적용 단위 | 포함 리소스 |
|---|---|
| `base/storage` | StorageClass, PV, PVC |
| `base/infrastructure` | PostgreSQL, Redis, Kafka |
| `base/platform` | Discovery, Config |
| `base/services` | 비즈니스 서비스, 준비된 경우 Spring AI |
| `base/gateway` | API Gateway |
| `overlays/learning` | 전체 base와 환경 patch |

Kustomize의 파일 나열 순서는 readiness를 보장하지 않는다. 최초 설치는 16절의 배포 파동대로 그룹별 적용과 대기를 수행하고, 전체 overlay 적용은 초기 인프라가 준비된 뒤 반복 배포에 사용한다.

## 14. 리소스 이름과 label

### 14.1 이름

- Kubernetes 리소스는 소문자 kebab-case를 사용한다.
- Deployment, StatefulSet과 주 Service는 같은 기본 이름을 사용한다.
- Stateful 데이터 PVC는 `<component>-data`를 사용한다.
- Local PV는 `<component>-local-pv`를 사용한다.
- headless Service는 `<component>-headless`를 사용한다.
- 환경 이름을 리소스 이름 앞에 붙이지 않는다. namespace와 label로 구분한다.
- 전역 `namePrefix`는 Service DNS 계약을 깨뜨릴 수 있으므로 사용하지 않는다.

### 14.2 공통 label

모든 namespaced workload와 Service의 base는 다음 label을 사용한다.

```yaml
app.kubernetes.io/name: <component-name>
app.kubernetes.io/part-of: prompthub
app.kubernetes.io/component: infrastructure|platform|service|gateway
app.kubernetes.io/managed-by: kustomize
```

`learning` overlay는 `app.kubernetes.io/instance: learning`을 추가한다.

Deployment·StatefulSet의 selector와 Pod template label은 정확히 일치해야 한다. selector 변경은 기존 리소스 교체가 필요하므로 단순 리네임으로 처리하지 않는다.

## 15. Workload 공통 계약

### 15.1 Deployment

- replica: 1
- strategy: `RollingUpdate`
- `maxSurge: 1`, `maxUnavailable: 0`
- `terminationGracePeriodSeconds: 30`
- `imagePullPolicy: IfNotPresent`
- `imagePullSecrets: ghcr-pull-secret`
- Large nodeSelector
- startup, readiness, liveness probe
- 필요한 Secret key만 개별 `secretKeyRef`로 주입
- Kubernetes API를 사용하지 않으면 ServiceAccount token 자동 mount 비활성화

### 15.2 StatefulSet

- replica: 1
- `podManagementPolicy: OrderedReady`
- `updateStrategy: RollingUpdate`
- 명시적인 PVC mount
- `spec.serviceName`은 먼저 생성된 headless Service를 참조
- nodeSelector와 Local PV nodeAffinity 일치
- PostgreSQL과 Redis는 Control Plane toleration 포함
- PostgreSQL과 Kafka의 `terminationGracePeriodSeconds`: 60

### 15.3 Probe

| 대상 | startup | readiness | liveness |
|---|---|---|---|
| Spring Boot | `/actuator/health` | `/actuator/health/readiness` | `/actuator/health/liveness` |
| PostgreSQL | `pg_isready` | `pg_isready` | `pg_isready` |
| Redis | `redis-cli ping` | `redis-cli ping` | `redis-cli ping` |
| Kafka | TCP 9092 | TCP 9092 | TCP 9092 |

Spring Boot startup probe는 10초 간격으로 최대 30회 실패를 허용한다. readiness는 10초 간격, liveness는 20초 간격을 초기 기준으로 사용한다.

## 16. 기동 순서와 의존성

```mermaid
flowchart LR
    A["1. Namespace · Secret"] --> B["2. StorageClass · PV · PVC"]
    B --> C["3. PostgreSQL · Redis · Kafka"]
    C --> D["4. Discovery"]
    D --> E["5. Config Server"]
    E --> F["6. 비즈니스 서비스 · Spring AI"]
    F --> G["7. API Gateway"]
    G --> H["8. NodePort 스모크 테스트"]
```

각 파동은 `kubectl wait` 또는 `kubectl rollout status`가 성공한 뒤 다음 단계로 진행한다.

Kubernetes에는 Compose `depends_on`이 없으므로 init container는 네트워크 endpoint만 기다린다.

| 워크로드 | init container 대기 대상 |
|---|---|
| 여섯 비즈니스 서비스와 Spring AI | Discovery, Config |
| DB 사용 서비스 | PostgreSQL 추가 |
| User | Redis 추가 |
| Kafka 사용 서비스 | Kafka 추가 |
| Gateway | Discovery, Config |

비즈니스 서비스끼리 init dependency를 만들지 않는다. 상호 의존은 Eureka, gRPC deadline, retry와 circuit breaker로 처리해 기동 교착을 방지한다.

## 17. 초기 자원 기준

아래 값은 첫 배포 기준이며 확정 용량이 아니다. 실제 부하에서 OOMKilled, CPU throttling, Pending, eviction과 사용량을 확인해 조정한다.

### 17.1 Medium

| Pod | CPU request / limit | Memory request / limit |
|---|---:|---:|
| PostgreSQL | 300m / 800m | 512Mi / 1Gi |
| Redis | 50m / 250m | 128Mi / 256Mi |

Control Plane과 OS를 위해 1~1.5GiB 이상의 메모리 여유를 유지한다.

### 17.2 Large

| Pod 그룹 | 수 | Pod당 CPU request / limit | Pod당 Memory request / limit |
|---|---:|---:|---:|
| Kafka | 1 | 250m / 700m | 768Mi / 1280Mi |
| Discovery, Config | 2 | 100m / 300m | 256Mi / 512Mi |
| 비즈니스 서비스 | 6 | 100m / 500m | 256Mi / 512Mi |
| API Gateway | 1 | 100m / 500m | 256Mi / 512Mi |
| Spring AI | 1 | 150m / 600m | 384Mi / 768Mi |

Spring JVM과 Kafka heap은 컨테이너 memory limit보다 작게 명시한다. 자원이 부족하면 모든 Pod 값을 일괄 변경하지 않고 실제 사용량과 재시작 원인을 기준으로 워크로드별로 조정한다.

## 18. 보안 계약

- SSH는 관리자 고정 IP에서만 허용한다.
- API Server 6443은 인터넷에 공개하지 않는다.
- Gateway NodePort 이외의 public inbound를 허용하지 않는다.
- 실제 Secret YAML, base64 값과 kubeconfig를 Git에 커밋하지 않는다.
- `secret.example.yaml`에는 key 이름과 가짜 예시만 둔다.
- GHCR credential은 `kubernetes.io/dockerconfigjson` Secret으로 관리한다.
- Pod는 필요한 Secret key만 주입받는다.
- 불필요한 ServiceAccount token mount를 끈다.
- Flannel 환경에서 NetworkPolicy가 집행된다고 가정하지 않는다.
- 이미지의 non-root 전환은 이미지 하드닝 작업으로 분리하고, 검증 없이 `runAsNonRoot`를 강제하지 않는다.

## 19. 변경 규칙

| 변경 | 함께 확인할 항목 |
|---|---|
| Service 이름 또는 포트 | Config Server, Secret 주소, Eureka와 gRPC client 설정 |
| container port | Service `targetPort`, probe, Config Server `server.port` |
| 이미지 | GHCR SHA 존재, rollout과 rollback 대상 |
| Secret key | `secret.example.yaml`, 소비 Pod의 `secretKeyRef` |
| node label | nodeSelector, PV nodeAffinity, Control Plane toleration |
| PVC 또는 hostPath | StatefulSet mount, EC2 디렉터리 권한, 데이터 초기화 정책 |
| Config Server 파일 | Config 이미지 재빌드, Config rollout, 소비 서비스 재시작 |
| resource requests/limits | 노드 allocatable, JVM/Kafka heap, Pending·OOM 기록 |

배포용 YAML을 직접 복사해 환경별 파일을 만들지 않는다. 공통 변경은 base에, 학습 환경에만 필요한 값은 overlay patch에 둔다.

## 20. 검증 계약

### 20.1 정적 검증

- 모든 패키지가 `kubectl kustomize`로 독립 렌더링된다.
- `kubectl apply --dry-run=client`가 성공한다.
- 클러스터 연결 후 `kubectl apply --server-side --dry-run=server`가 성공한다.
- 렌더링 결과에 `latest`, 실제 Secret 값, 미정 placeholder가 없다.
- Deployment·Service selector와 Pod label이 일치한다.
- 모든 Local PV에 올바른 nodeAffinity가 있다.

### 20.2 클러스터 검증

- Medium과 Large가 `Ready`다.
- CoreDNS와 Flannel Pod가 정상이다.
- 서로 다른 노드의 테스트 Pod가 Service DNS와 Pod IP로 통신한다.
- Local PV와 PVC가 의도한 노드에서 `Bound`다.
- PostgreSQL, Redis와 Kafka가 재생성 후 같은 PVC를 사용한다.
- Config endpoint가 서비스별 설정을 반환한다.
- Eureka에 Gateway와 배포된 서비스가 등록된다.
- HTTP와 실제 구현된 gRPC endpoint가 Service DNS로 호출된다.
- Large의 30080을 통한 로그인·상품 조회 스모크 테스트가 성공한다.
- 잘못된 SHA 이미지 적용 후 직전 SHA로 복구할 수 있다.

## 21. 구현 문서 경계

이 문서는 최종 리소스 계약을 설명하며 SSH 설치 명령이나 단계별 `kubectl` 명령을 담지 않는다.

- 의사결정과 전환 범위: `2026-07-14-kubeadm-kubernetes-migration-design.md`
- 클러스터 직접 설치 절차: `docs/guides/kubeadm-cluster-bootstrap.md`에서 작성
- 실제 매니페스트 사용법: `k8s/README.md`에서 작성
- 서비스 간 이벤트 흐름: `event-flow.md`
- Spring Cloud 동작: `spring-cloud.md`
- StatefulSet network identity: [Kubernetes StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- Service와 headless Service: [Kubernetes Service](https://kubernetes.io/docs/concepts/services-networking/service/)

명세와 매니페스트가 다르면 매니페스트만 조용히 수정하지 않는다. 구현 변경과 함께 이 문서를 갱신해 목표 상태와 실제 상태를 일치시킨다.
