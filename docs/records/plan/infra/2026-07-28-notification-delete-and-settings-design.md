# 알림 삭제 및 수신 설정 설계

## 1. 배경과 목표

현재 `notification-service`는 구매자의 주문·결제·환불 알림을 저장하고 목록 조회, 미읽음
수 조회, 읽음 처리와 SSE 실시간 전달을 제공한다. 알림은 생성 후 90일 동안 유지되지만 사용자가
직접 삭제할 방법이 없고, 카테고리별 수신 여부를 저장하거나 알림 생성에 반영하는 기능도 없다.

이 변경은 다음 기능을 추가한다.

- 사용자가 본인의 유효한 알림 하나를 물리 삭제한다.
- 사용자가 본인의 유효한 알림 전체를 물리 삭제한다.
- 사용자가 선택형 카테고리인 `PRODUCT`, `MARKETING`의 수신 여부를 조회하고 변경한다.
- 수신 거부는 변경 후 새로 들어오는 알림에만 적용한다.
- 거래·운영에 필요한 `ORDER`, `PAYMENT`, `REFUND`, `SYSTEM` 알림은 항상 수신한다.

API는 기존과 같이 Gateway가 전달하는 `X-User-Id`를 사용자 식별자로 사용하며 `BUYER`
권한 경로를 유지한다.

## 2. 범위와 정책

### 2.1 삭제 정책

- 단건 삭제와 전체 삭제를 모두 제공한다.
- 삭제는 soft delete가 아닌 실제 `notification` 행 삭제다.
- 단건 삭제는 요청 사용자 소유이면서 `expires_at > now`인 알림만 허용한다.
- 전체 삭제는 요청 사용자의 `expires_at > now`인 알림을 한 번의 벌크 쿼리로 삭제한다.
- 전체 삭제 대상이 없어도 성공한다.
- 만료 알림은 기존 매일 오전 3시 정리 작업에 맡긴다.
- 알림 삭제는 `notification_processed_event`와 알림 설정을 삭제하지 않는다.

### 2.2 수신 설정 정책

- 변경 가능한 카테고리는 `PRODUCT`, `MARKETING`이다.
- `ORDER`, `PAYMENT`, `REFUND`, `SYSTEM`은 항상 `enabled=true`이며 변경할 수 없다.
- 설정 행이 없는 선택형 카테고리는 `enabled=true`로 해석한다. 기존 사용자와 신규 사용자
  모두 별도 초기화 작업 없이 기본 수신 상태가 된다.
- 사용자가 명시적으로 설정을 변경하면 수신과 거부 상태 모두 저장한다.
- 설정 변경은 이후 알림 생성에만 적용하며 이미 저장된 알림에는 소급하지 않는다.
- 거부 상태에서 소비한 Kafka 이벤트는 나중에 동의 상태로 바뀌어도 소급 생성하지 않는다.

## 3. 아키텍처와 데이터 모델

알림 설정은 알림 생성 정책의 일부이므로 `user-service`가 아니라 `notification-service`가
소유한다. 사용자 ID는 다른 서비스의 식별자로 취급하며 서비스 간 데이터베이스 FK를 만들지
않는다.

### 3.1 `notification_setting`

```text
notification_setting
────────────────────────────────────────
setting_id   UUID PK
recipient_id UUID NOT NULL
category     VARCHAR(30) NOT NULL
enabled      BOOLEAN NOT NULL
created_at   TIMESTAMPTZ NOT NULL
updated_at   TIMESTAMPTZ NOT NULL

UNIQUE (recipient_id, category)
```

- `setting_id`는 애플리케이션에서 UUID로 생성한다.
- `(recipient_id, category)` 유일 제약으로 사용자별 카테고리 설정을 하나만 유지한다.
- 유일 제약 인덱스가 `recipient_id` 선두 조회를 지원하므로 별도 중복 인덱스는 만들지 않는다.
- `created_at`은 최초 생성 시각을 유지하고 변경 때마다 `updated_at`만 갱신한다.
- 변경 가능 카테고리 검증은 애플리케이션 계층에서 수행한다. 향후 선택형 카테고리를 추가할
  때마다 DB CHECK 제약을 수정해야 하는 결합은 만들지 않는다.

구현 구성요소의 책임은 다음과 같다.

- `NotificationSetting`: 사용자·카테고리별 저장 상태를 표현한다.
- `NotificationSettingRepository`: 사용자 설정 조회와 원자적 upsert를 제공한다.
- `NotificationSettingService`: 기본값 병합, 변경 가능 카테고리 검증과 설정 변경을 담당한다.
- `NotificationService`: 알림 생성 전 수신 가능 여부를 확인하고 삭제 유스케이스를 제공한다.
- `NotificationController`: 네 개의 신규 HTTP API 계약만 담당한다.

## 4. API 계약

모든 응답 body는 기존 공통 `ApiResult` 형식을 사용한다. 삭제 API는 body 없이
`204 No Content`를 반환한다.

### 4.1 알림 설정 조회

```http
GET /api/v1/notifications/settings
X-User-Id: {recipientId}
```

응답의 `settings`는 `NotificationCategory` 선언 순서로 여섯 카테고리를 모두 포함한다.
클라이언트가 필수 카테고리 정책을 별도로 내장하지 않도록 `configurable`을 함께 반환한다.

```json
{
  "success": true,
  "data": {
    "settings": [
      { "category": "ORDER", "enabled": true, "configurable": false },
      { "category": "PAYMENT", "enabled": true, "configurable": false },
      { "category": "REFUND", "enabled": true, "configurable": false },
      { "category": "PRODUCT", "enabled": true, "configurable": true },
      { "category": "SYSTEM", "enabled": true, "configurable": false },
      { "category": "MARKETING", "enabled": false, "configurable": true }
    ]
  },
  "message": "success"
}
```

### 4.2 카테고리 설정 변경

```http
PUT /api/v1/notifications/settings/{category}
X-User-Id: {recipientId}
Content-Type: application/json

{
  "enabled": false
}
```

```json
{
  "success": true,
  "data": {
    "category": "MARKETING",
    "enabled": false,
    "configurable": true,
    "updatedAt": "2026-07-28T04:00:00Z"
  },
  "message": "success"
}
```

- 최종 상태 전체를 지정하는 멱등 요청이므로 `PUT`을 사용한다.
- DB 행 유무와 관계없이 논리적 설정 리소스는 기본값으로 존재하므로 성공은 항상 `200 OK`다.
- 요청 DTO는 nullable `Boolean enabled`에 `@NotNull`을 적용해 누락과 명시적 `null`을
  모두 잘못된 입력으로 처리한다.

### 4.3 단건 알림 삭제

```http
DELETE /api/v1/notifications/{notificationId}
X-User-Id: {recipientId}
```

- 성공: `204 No Content`
- 존재하지 않음, 타인 소유 또는 만료 알림: `404 / N001`

세 경우를 같은 응답으로 처리해 다른 사용자의 알림 존재 여부를 노출하지 않는다.

### 4.4 전체 알림 삭제

```http
DELETE /api/v1/notifications
X-User-Id: {recipientId}
```

- 요청 사용자의 만료되지 않은 알림을 모두 삭제한다.
- 삭제 대상이 없어도 `204 No Content`를 반환한다.

삭제와 설정 변경은 별도 SSE 또는 Redis 이벤트를 발행하지 않는다. 요청한 클라이언트는 성공
응답을 기준으로 로컬 목록과 미읽음 수를 갱신하며 다른 탭은 다음 HTTP 조회 때 동기화한다.

## 5. 설정 저장과 알림 생성 흐름

### 5.1 설정 조회

`NotificationSettingService`는 사용자별 저장 행을 한 번 조회해 카테고리별 map으로 변환한다.
그 후 전체 `NotificationCategory`를 순회하면서 다음 규칙으로 응답을 만든다.

1. 필수 카테고리는 저장 행과 무관하게 `enabled=true`, `configurable=false`다.
2. 선택형 카테고리는 저장 행이 있으면 그 값을 사용한다.
3. 선택형 카테고리의 저장 행이 없으면 `enabled=true`를 사용한다.

### 5.2 설정 upsert

설정 변경은 하나의 트랜잭션에서 다음 순서로 처리한다.

1. `PRODUCT` 또는 `MARKETING`인지 검증한다.
2. 새 `setting_id`와 현재 시각을 준비한다.
3. PostgreSQL `INSERT ... ON CONFLICT (recipient_id, category) DO UPDATE`를 실행한다.
4. 충돌 시 기존 `setting_id`, `created_at`은 유지하고 `enabled`, `updated_at`만 갱신한다.
5. 저장된 최종 상태를 반환한 뒤 커밋한다.

원자적 upsert는 같은 사용자·카테고리의 최초 변경 요청이 동시에 들어와도 중복 행이나
불필요한 애플리케이션 재시도 로직이 생기지 않게 한다.

### 5.3 알림 생성 필터

수신 여부 검사는 `NotificationService.createNotification()` 안에서 수행해 Kafka 외의 향후
생성 경로에도 같은 정책을 적용한다.

```text
CreateNotificationCommand
  → 필수 카테고리: 설정 조회 없이 생성
  → 선택형 카테고리:
      설정 없음 또는 enabled=true → 생성
      enabled=false → 생성 생략
```

생성 메서드 반환형은 `Optional<NotificationResponse>`로 변경한다. 생성 생략은
`Optional.empty()`로 표현하고 실제 저장에 성공했을 때만 `NotificationCreatedEvent`를
발행한다.

`OrderEventHandler`의 processed-event claim, 설정 검사와 알림 생성은 기존처럼 같은
트랜잭션에 참여한다.

- 거부 상태는 정상 처리이므로 claim을 커밋하고 Kafka 메시지를 acknowledge한다.
- 설정 저장소 장애는 전체 트랜잭션을 롤백하므로 claim이 남지 않고 기존 Kafka 재시도 정책을
  적용한다.
- 현재 Kafka 이벤트는 모두 필수 카테고리이므로 설정 조회 쿼리를 추가로 실행하지 않는다.

설정 변경과 알림 생성이 동시에 실행되면 알림 생성 쿼리가 확인한 마지막 커밋 설정을 따른다.
설정 변경 API가 완료된 뒤 시작한 알림 생성은 변경된 값을 적용한다.

## 6. 삭제 흐름과 동시성

단건 삭제는 기존 소유자·유효기간 조회를 재사용한다.

```text
notificationId + recipientId + expiresAt > now 조회
  → 없음: N001
  → 있음: entity delete
  → commit
```

전체 삭제는 엔티티를 메모리에 적재하지 않고 다음 조건의 벌크 delete를 실행한다.

```sql
DELETE FROM notification
WHERE recipient_id = :recipientId
  AND expires_at > :now;
```

전체 삭제와 새 알림 생성이 동시에 실행되는 경우 delete 문이 대상으로 삼은 행만 삭제한다.
delete 실행 이후 생성된 새 알림은 유지한다. 알림 삭제는 새 알림 생성과 Kafka 처리 자체를
차단하는 기능이 아니다.

## 7. 오류 처리

새 비즈니스 오류는 하나만 추가한다.

| 코드 | HTTP | 메시지 | 조건 |
|---|---:|---|---|
| `N003` | 400 | 변경할 수 없는 알림 카테고리입니다. | 필수 카테고리 설정 변경 |

기존 오류는 다음과 같이 재사용한다.

| 상황 | 오류 |
|---|---|
| 알림 없음·타인 소유·만료 알림 단건 삭제 | `404 / N001` |
| 잘못된 category, body 또는 `X-User-Id` | `400 / V001` |
| 예상하지 못한 오류 | `500 / SYS001` |

현재 `NotificationExceptionHandler`의 잘못된 입력 처리 범위에
`MethodArgumentNotValidException`과 `HttpMessageNotReadableException`을 추가한다. 로그에는
기존 보안 정책대로 안정적인 오류 코드와 예외 타입만 기록하고 원문 입력값이나 예외 메시지는
기록하지 않는다.

## 8. 테스트 전략

### 8.1 설정 도메인과 서비스

- 설정 행이 없는 `PRODUCT`, `MARKETING`은 기본 수신한다.
- 저장된 설정과 기본값을 병합해 여섯 카테고리를 enum 순서로 반환한다.
- 필수 카테고리는 항상 수신하며 저장소 조회 없이 판정한다.
- 두 선택형 카테고리 설정을 변경할 수 있다.
- 필수 카테고리 변경은 `N003`이다.
- 같은 최종 상태를 반복 요청해도 응답과 저장 상태가 같다.

### 8.2 PostgreSQL 저장소

기존 Testcontainers PostgreSQL 의존성을 사용한 한 개의 집중 통합 테스트 클래스로 다음을
검증한다.

- Flyway V2가 `notification_setting`과 유일 제약을 생성한다.
- 최초 upsert는 행을 생성한다.
- 반복 upsert는 새 행을 만들지 않고 `enabled`, `updated_at`만 변경한다.
- 반복 upsert 뒤에도 `setting_id`, `created_at`은 유지된다.
- 사용자 또는 카테고리가 다르면 독립 행을 생성한다.

PostgreSQL 전용 `ON CONFLICT` 동작은 H2로 모사하지 않는다. 별도 대규모 E2E 환경은 추가하지
않는다.

### 8.3 알림 생성과 Kafka

- 선택형 카테고리가 수신 상태이면 저장하고 생성 이벤트를 발행한다.
- 선택형 카테고리가 거부 상태이면 저장과 이벤트 발행을 모두 생략한다.
- 필수 카테고리는 설정 저장소 조회 없이 생성한다.
- 거부 상태에서도 processed-event claim은 유지한다.
- 설정 조회 실패는 Kafka 처리 트랜잭션을 롤백한다.

### 8.4 삭제

- 본인의 유효한 알림을 단건 삭제한다.
- 없는·타인 소유·만료 알림은 `N001`이다.
- 전체 삭제는 본인의 유효한 알림만 삭제한다.
- 삭제 대상이 없어도 성공한다.
- processed-event와 설정 행은 유지한다.

### 8.5 HTTP 계약과 회귀

- 설정 조회·변경 요청과 응답 JSON을 검증한다.
- 단건·전체 삭제의 `204`를 검증한다.
- 잘못된 category, body, header는 `V001`이다.
- 필수 카테고리 변경은 `N003`이다.
- 단건 삭제 실패는 `N001`이다.
- Springdoc `/v3/api-docs`에 네 신규 API가 노출되는지 확인한다.
- `./gradlew :notification-service:test`로 기존 테스트와 신규 테스트를 함께 검증한다.

## 9. 최소 구현 원칙

이번 변경은 네 API와 알림 생성 시 카테고리 on/off 판정에 필요한 최소 구성만 추가한다.
현재 요구사항을 예상 가능한 모든 알림 기능으로 일반화하지 않는다.

- `user-service` 연동이나 설정 동기화 이벤트를 추가하지 않는다.
- 이메일·문자·모바일 푸시 채널 설정을 추가하지 않는다.
- 알림 빈도, 방해 금지 시간, 요약 발송과 사용자별 템플릿을 추가하지 않는다.
- 설정 변경 감사 이력이나 법적 동의 이력 테이블을 추가하지 않는다.
- soft delete, 휴지통, 삭제 복원과 삭제 SSE 이벤트를 추가하지 않는다.
- 전체 설정 일괄 변경, 설정 초기화와 관리자 설정 API를 추가하지 않는다.
- 현재 두 선택형 카테고리를 위해 범용 규칙 엔진이나 전략 프레임워크를 만들지 않는다.
- 기존 서비스 구조를 대규모 리팩터링하거나 새로운 외부 의존성을 추가하지 않는다.
- 현재 필수 카테고리 Kafka 경로에는 불필요한 설정 DB 조회를 추가하지 않는다.
- PostgreSQL upsert 경계만 실제 PostgreSQL로 검증하고 별도 종단간 테스트 인프라는 만들지
  않는다.

향후 실제 요구가 생기면 이 모델에 선택형 카테고리나 설정 필드를 추가하되, 이번 구현에서는
추측에 기반한 확장 기능을 선행하지 않는다.

## 10. 완료 기준

- 단건·전체 삭제 API가 소유권과 유효기간 정책을 지키며 동작한다.
- 설정 조회는 여섯 카테고리의 기본값과 변경 가능 여부를 정확히 반환한다.
- `PRODUCT`, `MARKETING`만 변경할 수 있고 설정 행은 사용자·카테고리별 하나만 존재한다.
- 거부한 선택형 카테고리는 저장과 SSE 발행이 모두 생략된다.
- 필수 알림과 기존 주문 Kafka 처리에는 동작 변경이나 추가 설정 조회가 없다.
- 설정 변경은 기존 알림에 소급하지 않고 거부 중 처리한 이벤트도 소급 생성하지 않는다.
- 오류 응답과 로그가 기존 공통 계약을 유지한다.
- 기존 38개 테스트를 포함한 `notification-service` 전체 테스트가 통과한다.
