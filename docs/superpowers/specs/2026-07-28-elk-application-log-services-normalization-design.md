# ELK 애플리케이션 로그 서비스 정상화 설계

## 1. 배경

이슈 #625에서 8개 비즈니스 서비스의 구조화 로그 수집 경로를 구축했지만, 현재
Kibana의 `application-logs-*`에서는 `product-service`와
`notification-service`만 확인된다.

ELK의 수집 허용 목록은 이미 다음 8개 서비스를 포함한다.

- `user-service`
- `product-service`
- `order-service`
- `payment-service`
- `admin-service`
- `ai-service`
- `settlement-service`
- `notification-service`

따라서 수집 대상을 추가하는 문제가 아니라, 구조화 로그 이미지가 전체 서비스에
배포되지 못한 상태를 정상화해야 한다.

배포 이력을 조사한 결과는 다음과 같다.

1. 전체 서비스 배포 실행은 Secret 계약 검증 실패 또는 `ai-service`의
   `CrashLoopBackOff` 때문에 완료되지 못했다.
2. 전체 배포 실행에서 먼저 갱신된 서비스도 실패 시 일괄 rollback되어 기존 이미지로
   돌아갔다.
3. 이후 `product-service`와 `notification-service`만 개별 배포에 성공했다.
4. 그 결과 구조화 JSON을 출력하는 두 서비스의 로그만 현재 수집된다.

`ai-service`의 직접 원인은 Config Server 프로필이
`${KAFKA_BOOTSTRAP_SERVERS}`를 필수 값으로 사용하지만, Kubernetes Deployment의
애플리케이션 컨테이너가 해당 Secret key를 환경 변수로 주입하지 않는 것이다. 기존
Secret 계약 검증은 저장소 전체에서 key가 한 번이라도 소비되는지만 확인하므로, 다른
서비스가 같은 key를 사용하는 상황에서 이 서비스별 누락을 발견하지 못한다.

## 2. 목표

- `ai-service`의 Kubernetes 환경 변수 계약을 수정해 정상 기동시킨다.
- 배포 서비스의 Config 필수 환경 변수가 같은 서비스의 애플리케이션 컨테이너에
  주입되는지 정적으로 검증한다.
- 서비스별 독립 배포로 나머지 6개 서비스의 구조화 로그 이미지를 안전하게 반영한다.
- Kibana에서 8개 서비스의 `INFO`, `WARN`, `ERROR` 로그를 `level` 필드로 조회할 수
  있음을 검증한다.
- HTTP 요청은 `requestId`로 Gateway와 애플리케이션 로그를 연결할 수 있음을 검증한다.

## 3. 범위

### 변경 범위

- `k8s/base/services/ai/deployment.yaml`
- `scripts/validate-k8s-secret-contract.sh`
- `scripts/test-validate-k8s-secret-contract.sh`
- `scripts/validate-k8s-manifests.sh`
- `k8s/addons/elk/README.md`
- 서비스별 Release workflow 실행과 운영 확인

### 제외 범위

- Fluent Bit과 Logstash의 8개 서비스 허용 목록 변경
- JSON 파싱 실패나 서비스 신원 불일치 이벤트를 허용하는 우회
- 전체 배포 workflow의 rollback 구조 재설계
- 서비스 애플리케이션 로깅 코드 변경
- settlement CronJob 실행 주기 변경
- 운영 환경에서 고의적인 `ERROR` 생성

## 4. 설계

### 4.1 `ai-service` 런타임 계약 수정

`ai-service` Deployment의 애플리케이션 컨테이너에
`KAFKA_BOOTSTRAP_SERVERS`를 `runtime-secret`의 동일한 key로 주입한다.

기존 AI init container는 Discovery, Config, Redis만 기다린다. 저장소의 AI 매니페스트
정책은 init container가 PostgreSQL·Kafka·User 서비스에 의존하지 않도록 보장하므로,
Kafka 대기를 추가하지 않는다. Kafka 연결 정보는 애플리케이션 컨테이너의 환경 변수로
주입하고 Spring Kafka가 애플리케이션 기동 단계에서 처리한다.

운영 Secret 값은 저장소에 추가하지 않는다. 기존 Secret 이름과 key 계약만 사용한다.

### 4.2 서비스 단위 Secret 계약 검증

기존 전역 검증은 유지한다.

- 배포 대상 Config 프로필의 기본값 없는 `${KEY}`가 예시 Secret에 존재해야 한다.
- 예시 Secret key는 실제 매니페스트 소비자 또는 승인된 예외와 연결되어야 한다.
- Kustomization에 등록된 서비스와 Config 프로필의 이름이 일치해야 한다.

여기에 다음 서비스 단위 검증을 추가한다.

1. `k8s/base/services/kustomization.yaml`의 직접 resource에서 배포 서비스 목록을
   구한다.
2. 각 서비스에 대응하는 `config/src/main/resources/configs/<service>.yml`에서
   기본값 없는 `${KEY}`를 추출한다.
3. 같은 서비스 Deployment 또는 CronJob의 애플리케이션 컨테이너 `env`에서
   환경 변수 이름을 추출한다.
4. Config 필수 key가 같은 워크로드의 애플리케이션 컨테이너에 없으면 실패한다.
5. 오류에는 서비스 이름, 누락 key, 확인한 Config 프로필과 워크로드를 표시한다.

init container의 `env`, 다른 서비스의 `env`, Secret 템플릿에 key가 존재한다는 사실은
같은 서비스의 주입 계약을 충족한 것으로 보지 않는다. 기본값이 있는
`${KEY:default}`는 런타임 필수 key가 아니므로 이 검증에서 제외한다.

`settlement-service`는 Deployment가 아니라 CronJob의 애플리케이션 컨테이너를 같은
규칙으로 검증한다. `notification-service`를 포함한 다른 배포 서비스도 별도
allowlist 없이 Kustomization과 프로필로부터 자동 검증 대상이 된다.

### 4.3 회귀 테스트

Secret 계약 테스트 fixture에 다음 시나리오를 추가한다.

1. `ai-service` 프로필이 `KAFKA_BOOTSTRAP_SERVERS`를 필수로 요구한다.
2. 예시 Secret과 다른 서비스에는 해당 key가 존재하지만 `ai-service`
   Deployment에는 환경 변수 주입이 없다.
3. 검증기가 `ai-service`와 누락 key를 포함한 오류로 실패해야 한다.
4. fixture의 `ai-service` 애플리케이션 컨테이너에 주입을 추가하면 성공해야 한다.

이 테스트는 전역 key 존재 여부만 확인하던 기존 검증의 사각지대를 재현하고, 이번
수정이 같은 회귀를 차단함을 증명한다.

AI 매니페스트 정적 검증은 전체 Deployment에 `KAFKA_`가 존재하는지를 검사하지 않고,
init container 블록 안에 `POSTGRES_`·`KAFKA_`·User 의존성이 없는지만 검사해야 한다.
애플리케이션 컨테이너의 `KAFKA_BOOTSTRAP_SERVERS` 주입은 허용한다.

### 4.4 순차 배포

하나의 이슈와 PR에서 변경을 추적하되, 운영 반영은 다음 순서로 서비스별 Release
workflow를 각각 실행한다.

1. `user-service`
2. `order-service`
3. `payment-service`
4. `admin-service`
5. `ai-service`
6. `settlement-service`

`product-service`와 `notification-service`는 이미 구조화 로그 이미지가 반영되어
있으므로 재배포 대상에서 제외하고 최종 8개 서비스 검증에는 포함한다.

각 서비스는 다음 게이트를 모두 통과한 뒤에만 다음 서비스로 진행한다.

- rollout 완료
- Deployment 또는 CronJob 이미지가 이번 빌드의 불변 digest와 일치
- 애플리케이션 stdout이 JSON 한 줄이며 `serviceName`, `level`, `message` 포함
- Elasticsearch `application-logs-*`에서 해당 `service.name` 문서 확인
- Kibana Data View에서 동일 문서 확인

하나라도 실패하면 다음 서비스를 배포하지 않는다. 실패한 서비스의 Pod describe,
현재·이전 컨테이너 로그, 이벤트, 이미지 digest를 수집해 원인을 해결한 뒤 해당
서비스부터 재개한다. 수집 확인을 위해 Logstash 검증을 완화하지 않는다.

`settlement-service`는 CronJob 매니페스트를 갱신한 뒤 고유 이름의 일회성 Job을
생성해 검증한다. 기존 schedule은 변경하지 않는다.

## 5. 로그 조회 검증

Kibana `Application Logs` Data View에서 다음 KQL로 서비스와 레벨을 확인한다.

```text
service.name: "user-service" and level: "INFO"
service.name: "user-service" and level: "WARN"
service.name: "user-service" and level: "ERROR"
```

`service.name`은 8개 서비스 각각으로 바꿔 확인한다. `level`은 keyword mapping이므로
`INFO`, `WARN`, `ERROR`를 대문자 정확 일치로 조회한다.

- `INFO`: 정상 요청이나 기동 로그로 확인한다.
- `WARN`: 유효하지 않은 요청처럼 데이터 변경이 없는 안전한 경로로 확인한다.
- `ERROR`: 기존 로그를 우선 조회하고, 필요하면 비운영 환경에서 통제된 실패로만
  생성한다. 운영 장애를 고의로 만들지 않는다.

특정 레벨의 결과가 0건인 것은 해당 레벨 이벤트가 아직 발생하지 않았다는 뜻일 수
있다. 이 경우 Data View와 mapping 문제인지 확인하기 위해 같은 서비스의 전체 로그,
`exists:level`, Elasticsearch 원본 문서를 함께 비교한다.

Gateway를 거치는 HTTP 요청에는 UUID 형식의 `X-Request-Id`를 사용하고,
`requestId: "<UUID>"`로 Gateway와 대상 서비스 문서가 함께 조회되는지 확인한다.
Kafka, gRPC, Redis Pub/Sub, settlement batch에는 request ID 전파를 새로 추가하지
않는다.

## 6. 검증

구현 정적 검증은 다음 명령을 모두 통과해야 한다.

```bash
bash scripts/test-validate-k8s-secret-contract.sh
bash scripts/validate-k8s-manifests.sh
./gradlew :ai-service:test
git diff --check
```

운영 검증은 다음을 만족해야 한다.

- 8개 서비스가 `application-logs-*`에서 각각 조회된다.
- 각 서비스 문서에 `service.name`, `level`, `message`가 존재한다.
- 발생한 `INFO`, `WARN`, `ERROR` 이벤트를 레벨별로 필터링할 수 있다.
- Servlet HTTP 요청은 동일한 `requestId`로 Gateway와 서비스 로그가 연결된다.
- 기존 `gateway-access-*`와 `products-v1` 수집은 영향을 받지 않는다.

## 7. 완료 기준

- `ai-service`가 Kafka 환경 변수 계약을 충족하고 rollout에 성공한다.
- 서비스 단위 Secret 계약 누락 회귀 테스트가 실패·성공 경로를 모두 검증한다.
- 나머지 6개 서비스가 순차 배포 게이트를 통과한다.
- Kibana에서 8개 서비스 로그와 로그 레벨별 조회 가능 여부가 증거와 함께 확인된다.
- 실패한 서비스가 있으면 다음 배포를 중단하고, 원인과 미완료 범위를 이슈에 기록한다.
