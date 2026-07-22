# 로컬 전체 스택 Docker Compose 설계

## 목적

현재 체크아웃한 소스를 컨테이너 이미지로 직접 빌드하고, PromptHub의 인프라와 모든 Spring 서비스를 로컬에서 한 번에 실행할 수 있는 `compose.yml`을 추가한다. EC2 배포용 `docker-compose.yml`과 인프라 전용 `compose.yaml`은 변경하지 않는다.

## 범위

`compose.yml`은 다음 컨테이너를 실행한다.

- 인프라: PostgreSQL, Kafka, Redis
- Spring Cloud: Discovery, Config Server
- 비즈니스 서비스: User, Product, Order, Payment, Settlement, Admin
- 진입점: API Gateway

각 Spring 서비스는 저장소 루트 `Dockerfile`과 모듈별 `MODULE_NAME`, `MODULE_PATH` 빌드 인자를 사용해 현재 소스에서 직접 빌드한다. GHCR 이미지는 사용하지 않는다.

## 네트워크와 포트

모든 컨테이너는 단일 Compose bridge network에서 서비스 이름으로 통신한다.

| 서비스 | 컨테이너 포트 | 호스트 포트 |
| --- | ---: | ---: |
| PostgreSQL | 5432 | 5432 |
| Kafka 외부 리스너 | 9092 | 9092 |
| Redis | 6379 | 6379 |
| Discovery | 8761 | 8761 |
| Config Server | 8888 | 8888 |
| API Gateway | 8000 | 8000 |
| User HTTP / gRPC | 18081 / 9081 | 8081 / 9081 |
| Product HTTP / gRPC | 18082 / 9082 | 8082 / 9082 |
| Order HTTP / gRPC | 18083 / 9083 | 8083 / 9083 |
| Payment HTTP / gRPC | 18084 / 9084 | 8084 / 9084 |
| Settlement HTTP | 18085 | 8085 |
| Admin HTTP | 18086 | 8086 |

HTTP 컨테이너 포트 `18081~18086`은 Config Server의 서비스별 설정을 따른다. Kafka는 컨테이너 클라이언트용 `kafka:29092`와 호스트 클라이언트용 `localhost:9092`를 분리해 광고한다.

## 설정과 비밀정보

Compose에는 로컬에서 재현 가능한 DB 이름, 계정, 네트워크 주소의 기본값을 둔다. JWT 키, Toss Payments 키, AWS S3 값은 루트 `.env`에서 주입하며 저장소에 실제 비밀값을 추가하지 않는다.

비즈니스 서비스는 `configserver:http://config:8888`에서 중앙 설정을 받고 `http://discovery:8761/eureka/`에 등록한다. PostgreSQL, Kafka, Redis, gRPC 주소는 Compose 서비스 이름을 사용한다. Config Server 설정에 남아 있는 직접 HTTP 호출 주소가 컨테이너 포트와 다르면 Compose 환경변수로 `1808x` 포트에 맞춘다.

## 데이터와 초기화

PostgreSQL과 Redis는 named volume을 사용해 `docker compose down` 이후에도 데이터를 유지한다. PostgreSQL은 기존 `docker-entrypoint-initdb.d`를 마운트해 서비스별 스키마와 역할을 초기화한다. 데이터 전체 초기화는 사용자가 명시적으로 `docker compose down -v`를 실행할 때만 발생한다.

## 기동과 실패 처리

기동 순서는 다음과 같다.

1. PostgreSQL, Kafka, Redis
2. Discovery
3. Config Server
4. 비즈니스 서비스
5. API Gateway

인프라에는 제공되는 진단 명령을 이용한 healthcheck를 둔다. Discovery와 Config Server는 선행 서비스가 준비된 뒤 시작하도록 의존 관계를 설정한다. 비즈니스 서비스는 인프라와 Spring Cloud 코어가 준비된 뒤 시작하고, Gateway는 비즈니스 서비스 컨테이너가 시작된 뒤 실행한다.

외부 연동용 값이 누락되거나 유효하지 않으면 해당 기능 또는 서비스가 명확히 실패하도록 하며, Compose 파일에 실제 키나 임의의 운영 자격 증명을 넣지 않는다.

## 검증

구현 후 다음을 검증한다.

1. `docker compose -f compose.yml config`가 성공하고 모든 필수 변수가 해석된다.
2. `docker compose -f compose.yml build`가 모든 Spring 서비스 이미지를 현재 소스에서 생성한다.
3. 전체 스택 기동 후 PostgreSQL, Kafka, Redis, Discovery, Config Server가 정상 상태다.
4. Eureka에 비즈니스 서비스와 Gateway가 등록된다.
5. `http://localhost:8000/actuator/health` 및 Gateway를 통한 공개 API 또는 Swagger 경로가 응답한다.
6. 직접 접근이 필요할 때 호스트의 `8081~8086`과 `9081~9084`가 각 컨테이너 포트로 연결된다.

환경이나 외부 자격 증명 때문에 전체 기동 검증이 불가능하면 Compose 정적 검증과 이미지 빌드 결과를 제공하고, 막힌 서비스와 필요한 값 또는 로그를 명시한다.
