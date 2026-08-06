# Config Server localhost fallback 하드코딩으로 개발서버 크래시 루프

**날짜**: 2026-07-09
**상태**: Resolved (PR #266, Closed #264)
**분류**: Deploy & Infra / Spring Cloud Config

## 환경

Spring Boot 4.1 / Spring Cloud Config (native/classpath 백엔드) / Docker Compose (EC2 self-hosted)

마이크로서비스 구성: discovery(Eureka) · config(Config Server) · user / product / order / payment / settlement / admin-service · apigateway

## 증상

개발서버(EC2)에서 `product-service`가 몇 초 단위로 계속 재시작했다.

```
$ docker ps -a --format 'table {{.Names}}\t{{.Status}}'
product-service   Up Less than a second   # 반복
```

```
$ docker logs product-service --tail 100
ConfigServerConfigDataLoader ... http://localhost:8888 ... Connection refused
```

`restart: always` 정책 때문에 컨테이너가 뜨자마자 죽고 무한 재시작하는 크래시 루프 상태였다.
ADR-0006(gRPC 포트 docker-compose 노출) 적용 이후, product-service gRPC 포트(9082)에 SSH
포트포워딩 + grpcurl로 접근하다가 "context deadline exceeded"로 막혀서 원인을 추적하다 발견했다.

## 핵심: `spring.config.import`는 override가 아니라 누적(additive) 처리된다

`product-service/src/main/resources/application.yml`에는 프로파일 조건 없이 아래가 박혀 있었다.

```yaml
spring:
  config:
    import:
      - optional:configserver:http://localhost:8888
```

한편 docker-compose는 모든 서비스에 정상 주소를 환경변수로 주입한다.

```yaml
environment:
  SPRING_CONFIG_IMPORT: optional:configserver:http://config:8888
```

직관적으로는 "환경변수가 yml보다 우선순위가 높으니 `SPRING_CONFIG_IMPORT`가 yml의 값을
덮어쓸 것"이라 예상했지만, 실제로는 그렇지 않았다. Spring Boot의 Config Data 처리는
`spring.config.import`를 일반 property override 규칙이 아니라, **각 활성 property source가
선언한 import를 모두 모아 누적 처리**한다. 그래서 로그에는 두 주소가 **둘 다** 나타났다.

```
Fetching config from server at : http://config:8888        ← 정상(env var)
Fetching config from server at : http://localhost:8888      ← 하드코딩(yml), 별개로 같이 시도됨
```

`optional:` 접두사가 붙어 있어도 `spring.cloud.config.fail-fast: true` +
`retry.max-attempts: 6` 조합에서는, `localhost:8888` 쪽 재시도가 소진된 뒤 fatal로 올라와
애플리케이션 컨텍스트가 죽었다.

## 조사 과정

### 1. 같은 패턴이 다른 서비스에도 있는지 확인

`product/order/payment-service`의 `application.yml`을 grep해보니 세 서비스 모두 프로파일
조건 없는 `optional:configserver:http://localhost:8888` 하드코딩을 갖고 있었다. 반면
`user-service`는 아래처럼 하드코딩 fallback이 없는 패턴을 쓰고 있었다.

```yaml
spring:
  config:
    import: ${CONFIG_IMPORT:}   # 환경변수 없으면 빈 값 → 아무것도 import 안 함
```

`settlement-service`는 또 다른 패턴이었다.

```yaml
spring:
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}
```

처음에는 이 패턴이 "env var로 주소를 우선시키는 안전한 방식"이라고 판단했으나, 위에서 확인한
"import는 누적 처리된다"는 사실을 적용하면 이 패턴도 여전히 취약하다. docker-compose는
`CONFIG_SERVER_URL`이 아니라 `SPRING_CONFIG_IMPORT`를 주입하므로, `CONFIG_SERVER_URL`은
설정되지 않은 채로 남아 이 줄은 항상 `optional:configserver:http://localhost:8888`로
해석되고, 이 역시 `SPRING_CONFIG_IMPORT`의 정상 import와 별개로 추가 시도된다.

### 2. fail-fast 유무로 실제 크래시 위험을 갈랐다

패턴이 위험해 보여도, 실제로 컨테이너가 죽는 것은 `spring.cloud.config.fail-fast: true` +
`retry`가 있을 때만이다(기본값은 `fail-fast: false` — optional import 실패 시 경고 로그만
남기고 계속 진행). 서비스별로 확인한 결과:

| 서비스 | 하드코딩 fallback | `fail-fast: true` | 크래시 루프 위험 |
| --- | --- | --- | --- |
| product / order / payment | 있음 | 있음 | **실제 발생** |
| apigateway / settlement-service | 있음 | 있음 | 있음 (미확인이지만 동일 패턴) |
| admin-service | 있음 | **없음** | 없음 (재발 방지 차원에서만 정리) |
| user-service | 없음 (`${CONFIG_IMPORT:}`) | 있음 | 없음 |

Config Server가 서빙하는 공통 설정(`config/src/main/resources/configs/application.yml`)에도
`fail-fast`가 없어, admin-service가 원격 설정을 받더라도 이 값을 상속하지 않는다는 것도
함께 확인했다.

## 해결

전 서비스(`user/product/order/payment/apigateway/settlement/admin-service`)를
user-service가 쓰던, 하드코딩 fallback이 없는 패턴으로 통일했다.

```yaml
spring:
  config:
    import: ${CONFIG_IMPORT:}
```

- docker-compose가 주입하는 `SPRING_CONFIG_IMPORT=optional:configserver:http://config:8888`는
  여전히 유효한 import로 별도 처리된다.
- yml 쪽 `${CONFIG_IMPORT:}`는 `CONFIG_IMPORT` 환경변수가 없으면 빈 문자열로 해석되어
  no-op — 더 이상 `localhost:8888` 시도가 추가되지 않는다.
- 로컬 개발에서 config server를 직접 쓰고 싶으면 `.env`에 `CONFIG_IMPORT=optional:configserver:http://localhost:8888`를
  명시적으로 설정하면 된다(옵트인 방식).

## 검증

7개 서비스 모두 `./gradlew :{service}:compileJava` 성공 확인(설정 파일만 바뀌어 코드
컴파일에는 영향 없음, 설정 정합성 확인 목적). 개발서버(EC2) 배포 후 `docker logs`로
크래시 루프 재발 여부와 config import 성공 로그(`Fetching config from server at :
http://config:8888` 한 줄만 남는지)를 확인해야 한다 — 공유 개발 인스턴스라 재기동 전
팀 공지가 필요했다.

## 교훈 / 재발 방지

- **`spring.config.import`는 여러 소스에서 온 선언을 override가 아니라 누적 처리한다.**
  "env var가 yml보다 우선순위가 높으니 하드코딩된 fallback은 안전하게 덮인다"는 직관은
  이 프로퍼티에는 적용되지 않는다. `spring.config.import`에 하드코딩 fallback을 두려면,
  그 값이 실제로 안전한 주소(예: 정상적으로 존재하는 로컬호스트)일 때만 허용한다.
- **`fail-fast: true` + `retry`가 있는 서비스에서 `optional:` prefix는 안전장치가 아니다.**
  재시도 소진 후에는 여전히 fatal로 올라온다.
- 새 서비스를 추가할 때 `spring.config.import`는 `${CONFIG_IMPORT:}` 패턴(하드코딩 fallback
  없음)을 기본값으로 삼는다. 로컬에서 config server를 쓰고 싶으면 `.env`로 옵트인한다.
- `fail-fast`가 없어 당장 크래시 위험이 없는 서비스(admin-service)라도, 같은 하드코딩
  fallback 패턴을 남겨두면 나중에 `fail-fast`가 추가되는 순간 같은 버그가 재발한다 — 패턴
  자체를 통일해 두는 편이 안전하다.
