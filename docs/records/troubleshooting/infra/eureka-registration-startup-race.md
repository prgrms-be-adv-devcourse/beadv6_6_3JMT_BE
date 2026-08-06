# 도커 배포 시 일부 서비스가 Eureka에 등록되지 않음

## 환경

Spring Boot 4.1 / Spring Cloud 2025.1.2 / Eureka(Netflix) / Spring Cloud Config / Docker Compose (EC2 self-hosted)

마이크로서비스 구성: discovery(Eureka) · config(Config Server) · user / product / order / payment / settlement-service · apigateway

## 증상

main 배포 후 일부 서비스가 Eureka에 등록되지 않았다. 유레카 목록을 조회하면 떠 있어야 할 서비스가 빠져 있다.

```
$ curl -s -H "Accept: application/json" http://localhost:8761/eureka/apps | jq -r '.applications.application[].name'
PAYMENT-SERVICE
SETTLEMENT-SERVICE
ORDER-SERVICE
# USER-SERVICE, APIGATEWAY 누락, settlement도 처음엔 누락
```

`docker ps`로는 모든 컨테이너가 `Up`인데 유레카에는 안 보인다. apigateway가 빠지면 라우팅 대상을 못 찾아 swagger가 503을 반환한다.

```
GET /order-service/v3/api-docs -> 503 Service Unavailable
```

등록 못 한 서비스 로그에는 다음이 반복된다.

```
POST "http://localhost:8761/eureka/apps/SETTLEMENT-SERVICE": Connect to http://localhost:8761 failed: Connection refused
registration failed Cannot execute request on any known server
```

서비스가 Eureka 주소를 `localhost:8761`로 잡고 있는데, 컨테이너 안에서 `localhost`는 자기 자신이라 별도 컨테이너인 discovery에 닿지 못한다. 원래는 `discovery:8761`(compose 네트워크 DNS)이어야 한다.

## 핵심: 두 개의 다른 원인이 섞여 있었다

증상은 "localhost:8761로 폴백돼 등록 실패"로 같지만, 폴백된 이유가 서비스마다 달랐다.

| | 문제 A — 기동 타이밍 | 문제 B — config 의존성 누락 |
| --- | --- | --- |
| 대상 | settlement (등 config 의존성 있는 서비스) | apigateway, user-service |
| 원인 | config server보다 **먼저 떠서** 설정을 못 받음 | config client가 **아예 없어서** 설정을 안 받음 |
| 재시작으로 풀리나 | 예 (config를 받을 능력은 있음) | 아니오 (코드 수정 필요) |

두 서비스 모두 Eureka 주소(`eureka.client.service-url.defaultZone`)가 **로컬 yml에 없고 Config Server에서만** 내려온다. 그래서 어떤 이유로든 config를 못 받으면 Spring Cloud Eureka 기본값인 `localhost:8761`로 폴백된다.

## 조사 과정

### 1. 환경변수는 정상인데 안 먹는다

compose에는 `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://discovery:8761/eureka/`가 주입돼 있고, 컨테이너에서도 보인다.

```
$ docker exec user-service env | grep -i eureka
EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://discovery:8761/eureka/
```

그런데도 `localhost:8761`로 간다. 이 프로젝트는 Eureka 주소를 Config Server의 yml 플레이스홀더(`${EUREKA_CLIENT_SERVICEURL_DEFAULTZONE:...}`)를 통해 주입한다. 즉 **config를 받아야 이 환경변수가 적용**된다. 환경변수 직접 바인딩(`EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` → `eureka.client.service-url.defaultZone` Map)은 키 대소문자(`defaultZone` vs `defaultzone`) 때문에 안정적으로 먹지 않는다.

### 2. 인스턴스 ID 형식으로 config 수신 여부를 가른다

정상 등록된 서비스와 안 된 서비스의 인스턴스 ID 형식이 다르다.

```
ORDER-SERVICE      order-service:55ebc309...        ← ${spring.application.name}:${random.value}  (config의 instance-id 패턴 적용 = config 받음)
SETTLEMENT-SERVICE ef49ad92dd6e:settlement-service:8085  ← 호스트명:앱:포트  (Spring Cloud 기본 패턴 = config 못 받음)
```

config를 받으면 Config Server에 정의된 `instance-id` 패턴이 적용되고, 못 받으면 기본 패턴이 된다. 이 차이로 "config 수신 실패"를 빠르게 판별할 수 있다.

### 3. config 부팅 로그 확인

```
$ docker logs settlement-service 2>&1 | grep -iE 'config server|Could not locate'
Fetching config from server at : http://config:8888
... http://config:8888/settlement-service/default: Connection refused
Could not locate PropertySource (... optional = true ...): Connection refused
```

settlement는 부팅 시점(`06:45`)에 config server에 붙으려 했지만 그때 config가 아직 안 떠 있었고, `optional:` import라 그냥 무시하고 진행했다 → **문제 A**.

### 4. apigateway·user-service는 config 호출조차 안 한다

apigateway 로그에는 `Fetching config` 자체가 없었다.

```
$ docker logs apigateway 2>&1 | grep -iE 'configserver|Fetching config'
(아무것도 없음)
```

build.gradle을 비교하니 차이가 드러났다.

```
settlement-service:  spring-cloud-starter-netflix-eureka-client + spring-cloud-starter-config  ✅
apigateway:          eureka-client만, config 없음  ❌
user-service:        eureka-client만, config 없음  ❌
```

`spring-cloud-starter-config`가 없으면 config client가 classpath에 없어, `SPRING_CONFIG_IMPORT=optional:configserver:...`가 있어도 `optional:`이라 조용히 무시된다(에러도 안 남) → **문제 B**.

### 왜 로컬은 되고 배포만 안 되나

config를 못 받으면 Eureka 주소가 기본값 `localhost:8761`로 폴백된다.

- 로컬: discovery도 서비스도 같은 머신(`localhost`)에 있으니 `localhost:8761`이 **우연히 정답** → 등록 성공
- 도커: 컨테이너가 분리돼 `localhost`는 자기 자신 → discovery(`discovery:8761`)에 못 닿음 → 실패

즉 config 의존성 누락은 원래부터 있던 잠복 버그인데, 로컬에서 `localhost`가 정답이라 가려져 있다가 컨테이너 환경에서 드러났다.

## 해결

### 문제 B — config 의존성 추가

apigateway·user-service의 build.gradle에 누락된 의존성을 추가한다.

```gradle
implementation 'org.springframework.cloud:spring-cloud-starter-config'
```

이제 config client가 활성화되어 `SPRING_CONFIG_IMPORT`로 Config Server에서 Eureka 주소를 받아 정상 등록된다.

### 문제 A — 기동 순서 보장

기동 순서를 두 층위로 맞춘다. 둘 다 docker-compose의 `depends_on`으로 표현한다.

**(1) 인프라·코어 먼저 — config·discovery는 `healthy`까지 대기**

config·discovery가 완전히 기동된 뒤에 앱이 시작되도록, 둘에 healthcheck를 달고 앱의 `depends_on`을 `condition: service_healthy`로 건다. 단순 `service_started`(컨테이너 시작)는 부족하다 — 컨테이너가 떠도 Config Server·Eureka 앱이 요청을 받을 준비가 안 됐을 수 있어서, "포트가 살아있는지(health 통과)"까지 기다려야 한다.

```yaml
  discovery:
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8761/actuator/health || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 30s

  config:
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8888/actuator/health || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 40s
    depends_on:
      discovery:
        condition: service_healthy
```

`curl`은 베이스 이미지(`eclipse-temurin:21-jre`)와 config·discovery 컨테이너에 존재하며, actuator `/health`는 이미 노출돼 있다(CD 헬스체크도 같은 엔드포인트를 쓴다).

**(2) 서비스 간 의존 — gRPC 피호출 서비스가 먼저**

config·discovery만 맞추면 끝이 아니다. 서비스끼리 gRPC로 호출하기 때문에, **호출당하는 쪽이 먼저 떠 있어야** 한다.

- `product-service` → `user-service` (user gRPC)
- `order-service` → `user-service`, `product-service`
- `settlement-service` → `user-service`, `product-service`
- `apigateway` → 모든 서비스(라우팅 대상)

그래서 각 서비스의 `depends_on`에 자기가 호출하는 서비스를 함께 명시한다. 이러면 `user`·`product` → `order`·`settlement` → `apigateway` 순으로 바통을 넘기듯 차례차례 올라온다. 이 서비스 간 의존은 "시작됨"이면 충분하므로 `service_started`로 둔다.

```yaml
  user-service:           # 코어에만 의존 (피호출 대상 없음)
    depends_on:
      discovery: { condition: service_healthy }
      config:    { condition: service_healthy }
      # postgres / kafka 는 service_started

  settlement-service:     # user·product의 gRPC를 호출 → 둘이 먼저
    depends_on:
      discovery:       { condition: service_healthy }
      config:          { condition: service_healthy }
      user-service:    { condition: service_started }
      product-service: { condition: service_started }
```

정리하면, **인프라(config·discovery)는 `service_healthy`로 "완전히 준비될 때까지", 서비스 간 의존은 `service_started`로 "먼저 시작은 되도록"** 두 단계로 순서를 보장한다.

## 검증

CD 재배포 후:

```
config       Up (healthy)      ← healthcheck 동작
discovery    Up (healthy)
USER-SERVICE -> UP             ← 우회 없이 정상 등록
APIGATEWAY   -> UP
... 6개 전부 UP
/order-service/v3/api-docs -> 200   ← swagger 라우팅 정상
```

user-service·apigateway 로그에 `Fetching config from server` + `registration status: 204`가 찍히고, 인스턴스 ID도 config 패턴(`user-service:해시`)으로 바뀌어 config 수신이 확인된다.

## 교훈 / 재발 방지

- **Eureka 주소가 Config Server 경유로만 주입되는 구조라면, config 수신 실패 = Eureka 등록 실패로 직결**된다. config client 의존성과 기동 순서 둘 다 보장해야 한다.
- "로컬에선 되는데 배포만 안 됨"은 `localhost` 기본값이 로컬에서만 정답이라 생기는 전형적 패턴이다. 컨테이너 환경에서는 호스트명(서비스명)으로 닿는지 확인한다.
- **인스턴스 ID 형식**(기본 패턴 vs config의 instance-id 패턴)은 config 수신 여부를 빠르게 가르는 단서다.
- 운영 장애 복구 시 **수동 `docker run` 우회는 피한다.** compose 관리 밖 컨테이너가 남으면 다음 CD `compose up`이 같은 이름으로 충돌해 배포가 통째로 깨진다. 우회가 불가피하면 compose override(`-f`)로 하거나, CD 전에 반드시 정리한다.
