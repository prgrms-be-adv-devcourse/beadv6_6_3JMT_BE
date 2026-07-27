# ELK 서비스별 애플리케이션 로그 수집 설계

## 1. 배경과 목표

현재 `k8s/addons/elk`는 API Gateway의 `GATEWAY_ACCESS` 로그만 수집한다. 이 구성에서는
내부 서비스의 경고·예외 로그를 Kibana에서 조회하거나 Gateway 요청과 서비스 로그를 같은
요청 ID로 연결할 수 없다.

이 변경은 이슈 #625를 기준으로 다음을 제공한다.

- 7개 비즈니스 워크로드의 SLF4J 로그를 `application-logs-*`에 저장한다.
- Kibana에서 `service.name`, `level`, `requestId`로 로그를 검색한다.
- HTTP 요청은 Gateway가 전달한 `X-Request-Id`를 서비스 로그의 `requestId`로 유지한다.
- 기존 `gateway-access-*`, `products-v1`과 애플리케이션 로그의 보존 정책을 분리한다.

수집 대상은 `user-service`, `product-service`, `order-service`, `payment-service`,
`admin-service`, `ai-service`, `settlement-service`로 고정한다. `config`, `discovery`,
`notification-service`, API Gateway 일반 로그, init container와 인프라 로그는 제외한다.

## 2. 애플리케이션 로그 계약

7개 서비스는 Spring Boot의 Logstash structured console logging을 사용한다. SLF4J의
`INFO`, `WARN`, `ERROR` 로그는 JSON 한 줄로 stdout에 기록하고 운영 root level은 `INFO`로
설정한다. 기존 local profile의 서비스별 `DEBUG` 설정은 유지한다.

각 JSON 이벤트는 다음 검색 필드를 제공한다.

- `@timestamp`: 이벤트 발생 시각
- `service.name`: Kubernetes 워크로드와 일치하는 서비스 이름
- `level`: 로그 레벨
- `message`: 로그 메시지
- `requestId`: HTTP 요청 로그의 UUID 요청 ID
- `kubernetes.container_name`, `kubernetes.pod_name`: 수집기가 부여하는 실행 위치

Spring Boot 출력에는 `serviceName=${spring.application.name}`을 추가하고 Logstash가 이를
`service.name`으로 정규화한다. Kubernetes의 애플리케이션 라벨과 container name, JSON의
`serviceName`이 허용 목록의 같은 서비스임을 확인한 이벤트만 저장한다.

HTTP 추적은 `common-module`의 Servlet 자동 설정으로 제공한다. 필터는 UUID 형식의
`X-Request-Id`를 MDC `requestId`에 넣는다. 헤더가 없거나 UUID가 아니면 새 UUID를 생성하며,
요청 처리가 끝나면 `finally`에서 MDC 값을 제거한다. 이 계약은 Servlet 요청에만 적용한다.
Kafka, gRPC, settlement CronJob에는 요청 ID를 전파하거나 새 correlation ID를 만들지 않는다.

## 3. 수집 파이프라인

Fluent Bit은 Gateway용 input과 분리된 application input을 사용한다. application input은
`prompthub` namespace의 컨테이너 로그를 별도 DB로 tail하고 정확한 7개 container name만
통과시킨다. Gateway, `wait-for-*` init container와 그 밖의 컨테이너는 Logstash로 보내지
않는다.

Logstash는 HTTP input에서 받은 이벤트를 Gateway와 application 경로로 분기한다.

1. Kubernetes 메타데이터와 stdout의 JSON을 파싱한다.
2. Gateway의 `GATEWAY_ACCESS`는 기존 `gateway-access-*` 변환과 출력을 유지한다.
3. application 이벤트는 서비스 신원을 교차 검증하고 검색 필드를 정규화한다.
4. 민감한 key와 문자열 값을 재귀적으로 `[REDACTED]` 처리한다.
5. 수집기 envelope, 임시 metadata와 불필요한 필드를 제거한다.
6. 정규화된 이벤트를 `application-logs-%{+YYYY.MM.dd}`에 저장한다.

마스킹 대상은 대소문자와 `_`, `-`, 공백 변형을 포함한 `authorization`, `cookie`,
`password`, `secret`, `api key`, `token`, `body`다. 애플리케이션의 HTTP 예외 처리기는
원문 예외 메시지, 검증 입력값과 stack trace를 로그에 전달하지 않고 `errorCode`와 예외
타입만 기록한다. Logstash 마스킹은 이 애플리케이션 규칙을 보완하는 2차 방어선이다.

## 4. 저장·검색·배포

`application-logs-*`에는 전용 index template과 `application-logs-7d` ILM을 적용한다.
인덱스는 shard 1개, replica 0개를 사용한다. Gateway의 14일 ILM과 Product Service의
`products-v1`에는 영향을 주지 않는다.

Kibana bootstrap Job은 `application-logs-*` Data View와 `Application Logs` 저장 검색을
멱등하게 import한다. 기본 열은 `service.name`, `level`, `requestId`,
`kubernetes.container_name`, `message`다.

self-hosted Kubernetes 수동 CD workflow에는 `elk` 대상을 복구한다. 이 배포는 server-side
dry-run 후 ELK 리소스를 적용하고 application ILM·Kibana bootstrap Job을 재생성한 뒤
Logstash와 Fluent Bit rollout, 두 Job의 완료를 확인한다. 첫 배포부터 7개 서비스를 모두
활성화하며 Product-only canary 선택지는 제공하지 않는다.

## 5. 실패 처리와 안전성

- JSON 파싱 실패, 허용 목록 밖 컨테이너, 서비스 신원 불일치 이벤트는 저장하지 않는다.
- Fluent Bit은 filesystem buffering과 무제한 retry를 유지하되 output storage limit은
  `512M`으로 제한한다.
- Logstash 또는 Elasticsearch 장애 중에도 기존 Gateway 파이프라인 설정을 변경하지 않는다.
- Elasticsearch 10GiB 용량은 배포 후 `_cat/indices`와 일별 store size로 확인한다.
  7일 예상 사용량이 안전 여유를 초과하면 전체 수집을 유지한 채 로그 레벨 또는 보존 기간
  변경을 별도 이슈로 처리한다.
- ELK 포트는 NodePort나 Ingress로 공개하지 않고 기존 port-forward와 SSH tunnel 방식을
  유지한다.

## 6. 검증 기준

- 공통 MDC 필터가 유효한 요청 ID 유지, 누락·잘못된 값 교체, 요청 종료 후 제거를 검증한다.
- 7개 서비스 설정에 structured JSON과 올바른 service name이 존재함을 검증한다.
- 렌더된 Fluent Bit 설정이 7개 서비스만 허용하고 Gateway/init container를 제외함을 검증한다.
- 렌더된 Logstash 설정이 서비스 신원 검증, 마스킹, `application-logs-*` 출력과 7일 ILM을
  포함함을 검증한다.
- 민감 key, Bearer/JWT와 key-value 문자열 fixture가 Elasticsearch 출력 전에
  `[REDACTED]`로 바뀌는지 검증한다.
- 기존 Gateway access 인덱스·14일 ILM 및 `products-v1` 비적용 계약을 회귀 검증한다.
- 각 변경 서비스의 테스트와 전체 Kubernetes manifest/CD workflow 검증을 통과한다.
- 운영 확인에서는 7개 서비스별 로그, 동일 `requestId`의 Gateway·HTTP 서비스 로그,
  settlement CronJob 로그, 일일 인덱스 크기를 확인한다.

## 7. 제외 범위

- Kafka producer/consumer와 gRPC client/server의 request ID 전파
- settlement 실행 correlation ID
- config, discovery, notification-service 로그 수집
- Elasticsearch PVC 증설, 인증·TLS 전환
- 서비스별 개별 인덱스 또는 개별 보존 정책
