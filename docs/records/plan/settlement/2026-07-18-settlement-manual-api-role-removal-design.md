# 정산 수동 배치 API 로컬 전용화와 X-User-Role 제거 설계

- 작성일: 2026-07-18
- 관련 이슈: #394 `[FEATURE] admin·settlement 정산 API의 X-User-Role 계약 제거`
- 작업 브랜치: `feat/#394-remove-x-user-role-contract`

## 1. 배경

API Gateway가 인증과 역할 기반 인가를 담당하고, 하위 서비스에는 사용자 식별자인
`X-User-Id`만 전달하는 방향으로 인증 계약이 바뀐다. 현재 settlement-service의 관리자
인터셉터는 `X-User-Role: ADMIN`을 직접 확인하므로, 변경된 Gateway 계약에서는 정상적인
요청도 인증 실패로 처리된다.

일반 정산 관리 API(조회, 승인, 보류, 지급, 취소)는 이미 admin-service로 이동했다.
settlement-service에 남은 HTTP 진입점은 정산 배치를 수동 실행하고 상태를 조회하는
`SettlementBatchController`뿐이다. 이 진입점은 운영 관리 화면을 위한 API가 아니라 로컬
Swagger 및 HTTP 테스트에서 배치 실행을 확인하기 위한 보조 수단이다.

settlement-service의 최종 운영 형태는 Kubernetes CronJob이 배치 프로세스를 실행하는
구조다. 다만 현재 저장소에는 Deployment, 애플리케이션 내부 `@Scheduled`, 수동 HTTP API가
함께 존재하므로 CronJob 전환이 끝나기 전의 과도기 상태다.

## 2. 목표

1. settlement-service가 `X-User-Role`에 의존하지 않도록 한다.
2. 정산 배치의 소유권은 settlement-service에 유지한다.
3. 수동 배치 API는 로컬 테스트에서만 명시적으로 활성화한다.
4. 운영 기본 설정에서는 불필요한 HTTP 진입점과 OpenAPI 구성을 만들지 않는다.
5. admin-service 정산 코드에 역할 헤더 의존성이 없음을 검증하되 불필요한 변경은 하지 않는다.

## 3. 핵심 결정

### 3.1 운영 인가는 Gateway에서 끝낸다

운영 요청의 인증과 ADMIN 역할 검증은 API Gateway의 책임이다. settlement-service는 역할
헤더를 다시 검사하지 않으며 `X-User-Role`을 요청 계약이나 문서에 노출하지 않는다.

따라서 다음 구성을 제거한다.

- `AdminAuthorizationInterceptor`
- `AdminAuthorizationInterceptorTest`
- 인터셉터만 등록하는 `WebConfig`
- `AuthHeaders.USER_ROLE`
- `AuthHeaders.ADMIN_ROLE`

### 3.2 수동 배치 API는 settlement-service에 둔다

수동 실행 API를 admin-service로 옮기지 않는다. API가 호출하는 배치 유스케이스와 Spring
Batch 구성은 settlement-service의 책임이며, 로컬 테스트 진입점까지 admin-service로 옮기면
배치 모듈 간 결합만 늘어난다.

운영에서 관리자가 재실행을 요청해야 하는 요구가 생기면 admin-service가 Kubernetes Job을
생성하는 별도 제어면(control plane)을 설계한다. 배치 엔진 자체를 admin-service로 옮기지는
않는다.

### 3.3 명시적 기능 플래그로 로컬에서만 활성화한다

`SettlementBatchController`와 해당 API를 위한 `OpenApiConfig`에 다음 조건을 적용한다.

```yaml
settlement:
  manual-api:
    enabled: false
```

- 공통 기본값: `false`
- 로컬 설정: `true`
- 관련 테스트: 필요한 테스트 컨텍스트에서 `true`를 명시

`@Profile("local")` 대신 기능 플래그를 사용한다. 프로필 이름에 동작을 결합하지 않고,
통합 테스트나 임시 검증 환경에서도 목적을 드러내며 선택적으로 활성화할 수 있기 때문이다.

### 3.4 수동 실행자의 X-User-Id는 유지한다

수동 실행 POST 요청의 `X-User-Id`는 `actorId`로 배치 실행 명령에 전달되므로 유지한다.
이는 역할 검증 용도가 아니라 수동 실행자를 기록하기 위한 식별 정보다. 상태 조회 GET은
현재와 같이 별도의 사용자 헤더를 요구하지 않는다.

## 4. 실행 흐름

### 4.1 로컬 수동 실행

1. `application-local.yml`이 `settlement.manual-api.enabled=true`를 설정한다.
2. 수동 배치 컨트롤러와 OpenAPI 빈이 생성된다.
3. 사용자가 Swagger 또는 HTTP 요청으로 `X-User-Id`와 실행 대상 월을 전달한다.
4. 컨트롤러가 `RunSettlementBatchUseCase`를 호출한다.
5. 사용자는 반환된 Job Execution ID로 상태를 조회한다.

이 흐름에는 `X-User-Role`과 서비스 내부 ADMIN 검증이 없다.

### 4.2 현재 운영 과도기

기본 설정에서는 수동 컨트롤러와 해당 OpenAPI 빈이 생성되지 않는다. 기존 애플리케이션 내부
스케줄러의 동작은 이번 이슈에서 변경하지 않는다. 따라서 CronJob 전환 전까지 자동 실행
방식은 기존과 동일하다.

### 4.3 목표 운영 형태

Kubernetes CronJob이 settlement-service 배치 이미지를 주기적으로 실행한다. 이 전환이
완료되면 Deployment, Service, Gateway의 정산 배치 라우트, 애플리케이션 내부 스케줄러의
필요 여부를 함께 정리한다. 해당 인프라 전환은 #394와 분리한다.

## 5. API와 오류 처리

수동 API가 활성화된 환경의 계약은 다음과 같다.

- POST 요청에서 `X-User-Id`가 없거나 형식이 잘못되면 Spring MVC의 표준 요청 오류로
  처리한다.
- settlement-service는 역할 부재나 역할 불일치를 이유로 401 또는 403을 반환하지 않는다.
- 요청 값 검증, Job 중복 실행, 배치 실행 실패, 실행 이력 없음 등 기존 도메인 및 배치 오류는
  그대로 유지한다.
- 운영 경로에서 인증 실패와 ADMIN 권한 부족은 Gateway가 처리한다.

Swagger 설명에서는 `ADMIN 권한 필요`, `X-User-Role`, 서비스 내부 401/403 응답 설명을
제거하고 이 API가 로컬 수동 검증용임을 명시한다. OpenAPI 보안 스키마에는 `X-User-Id`만
남긴다.

## 6. 변경 범위

### 6.1 settlement-service

구현 단계에서 다음 파일을 변경한다.

- 삭제
  - `src/main/java/com/prompthub/settlement/global/web/AdminAuthorizationInterceptor.java`
  - `src/main/java/com/prompthub/settlement/global/config/WebConfig.java`
  - `src/test/java/com/prompthub/settlement/global/web/AdminAuthorizationInterceptorTest.java`
- 수정
  - `src/main/java/com/prompthub/settlement/global/web/AuthHeaders.java`
  - `src/main/java/com/prompthub/settlement/global/config/OpenApiConfig.java`
  - `src/main/java/com/prompthub/settlement/presentation/controller/SettlementBatchController.java`
  - `src/main/resources/application.yml`
  - `src/main/resources/application-local.yml`
  - 수동 API 조건부 활성화를 검증하는 테스트
  - 정산 모듈 내부의 현재 API 또는 운영 구조 설명 문서

### 6.2 admin-service

admin-service의 정산 패키지는 현재 `X-User-Role`을 읽지 않는다. 상태 변경 API가 실행자
식별을 위해 `X-User-Id`를 받는 동작은 유지한다. 회귀 검색과 관련 테스트만 수행하고 소스
변경은 하지 않는다.

### 6.3 이번 이슈에서 변경하지 않는 범위

- API Gateway의 헤더 전달 정책과 라우트 정의
- Kubernetes CronJob, Deployment, Service 매니페스트
- admin-service에서 Kubernetes Job을 생성하는 운영 재실행 API
- 배치 엔진과 Spring Batch 구성을 admin-service로 이동하는 작업
- user-service의 `/internal/authorize` 응답에 포함된 `role`
- Gateway 내부 역할 모델과 경로별 권한 정책
- 과거 의사결정 기록에서 당시 구조를 설명하는 `X-User-Role` 문구

루트 API 명세, Gateway, Kubernetes 파일은 settlement-service 모듈의 변경 권한 밖이므로
이번 구현에서는 수정하지 않고 후속 작업 대상으로 남긴다.

## 7. 검증 전략

### 7.1 조건부 빈 검증

- 기능 플래그가 없거나 `false`이면 `SettlementBatchController`가 생성되지 않는다.
- 기능 플래그가 `true`이면 컨트롤러가 생성되고 기존 실행 및 상태 조회 동작이 유지된다.
- OpenAPI 빈도 같은 조건으로 생성되며 보안 스키마에는 `X-User-Id`만 존재한다.

### 7.2 API 검증

- POST 요청은 `X-User-Id`만으로 정상 접수된다.
- POST 요청에 `X-User-Role`이 없어도 실패하지 않는다.
- POST 요청에 `X-User-Id`가 없으면 요청 오류가 발생한다.
- GET 상태 조회는 기존과 같이 사용자 헤더 없이 동작한다.

### 7.3 회귀 검증

- settlement-service의 런타임 코드, 테스트, 현재 API 문서에 역할 헤더 상수가 남지 않는다.
- admin-service 정산 패키지에 `X-User-Role` 직접 참조가 없음을 검색으로 확인한다.
- settlement-service 전체 테스트를 실행한다.

## 8. 고려한 대안

### 8.1 수동 API를 완전히 삭제

최종 CronJob 구조에는 가장 단순하지만, 현재 팀이 사용하는 로컬 Swagger 및 HTTP 기반 배치
검증 경로도 사라진다. 로컬 검증 수요가 확인되었으므로 선택하지 않는다.

### 8.2 수동 API를 admin-service로 이동

운영 관리 API와 주소 체계는 자연스러워 보이지만, 로컬 테스트만을 위해 admin-service가
settlement-service의 배치 유스케이스나 내부 실행 방식을 알아야 한다. 운영 재실행 제어면을
설계하는 별도 요구가 생길 때 검토한다.

### 8.3 `local` 프로필로만 제한

구현은 간단하지만 동작 조건이 프로필 이름에 묶이며 테스트와 임시 환경에서 재사용하기
어렵다. 기본 비활성인 명시적 기능 플래그가 의도를 더 잘 드러낸다.

### 8.4 항상 노출하고 Gateway에만 의존

역할 검증 책임은 올바르게 Gateway에 남지만, 운영에서 사용하지 않는 컨트롤러와 문서가
계속 활성화된다. CronJob 전용 서비스라는 목표 구조와 맞지 않아 선택하지 않는다.

## 9. 위험과 대응

- 로컬 설정에서 기능 플래그를 누락하면 수동 API가 보이지 않는다. 로컬 설정 파일과 개발
  문서에 활성화 방법을 함께 기록한다.
- 운영에서 기존 수동 API를 호출하던 주체가 있다면 비활성화 후 호출이 실패한다. Gateway
  라우트와 호출 주체를 구현 전에 검색하고, 운영 수동 재실행 요구가 확인되면 별도 이슈로
  분리한다.
- 조건부 컨트롤러만 적용하고 OpenAPI를 항상 생성하면 운영에 의미 없는 문서 빈이 남는다.
  컨트롤러와 OpenAPI에 동일한 조건을 적용하고 테스트한다.
- CronJob 전환과 이번 작업을 한 번에 처리하면 애플리케이션과 인프라 변경 위험이 커진다.
  #394는 헤더 계약 및 로컬 API 경계에 한정한다.

## 10. 완료 기준

- 승인된 구현 계획이 이 설계를 기준으로 작성된다.
- settlement-service의 수동 API가 기본 비활성, 로컬 활성으로 정의된다.
- settlement-service에서 `X-User-Role` 검증과 관련 구성의 제거 범위가 빠짐없이 계획된다.
- admin-service 정산 코드는 역할 헤더 제거를 위해 변경할 필요가 없다는 검증 절차가 포함된다.
- CronJob 전환과 운영 재실행 제어면은 후속 범위로 명확히 분리된다.
