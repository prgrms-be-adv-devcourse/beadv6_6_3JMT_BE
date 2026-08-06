# Refund reason/failure_reason 분리 및 failed_at 컬럼 추가 구현 계획

`refund.reason` 하나가 "사용자 환불 요청 사유"와 "환불 실패 사유"를 겸용해 실패 시 원 사유를 덮어써 유실시키는 문제를 컬럼 분리로 해결하고, 환불 실패 시각도 엔티티 자체 컬럼(`failed_at`)으로 보존해 감사로그가 이벤트 파라미터의 근사치(`now()`) 대신 정확한 값을 쓰도록 한다.

---

## 배경 및 목표

[#484 감사로그 작업](484-payment-audit-log.md) 브레인스토밍 중 발견한 문제. `Refund.java`의 `reason` 필드는 `create()` 시점엔 사용자가 입력한 환불 사유를 담지만, `fail(String reason)` 호출 시 같은 컬럼이 실패 사유로 **덮어써진다**(`Refund.java:103-110`).

```java
public void fail(String reason) {
    ...
    this.reason = reason;  // 사용자 원 사유 → 실패 사유로 overwrite, 복구 불가
}
```

`db-schema.md`에 이미 이 동작이 문서화("환불 사유 또는 실패 사유(`fail()` 호출 시 실패 사유로 갱신됨)")돼 있어 팀이 의도적으로 만든 설계지만, 환불이 실패한 건에 대해 "애초에 왜 환불을 요청했었는지"를 나중에 조회할 수 없다는 데이터 유실 문제가 있다.

`payment` 테이블은 이미 이 문제를 겪지 않는 비대칭 구조다 — `payment_method`/`provider` 등 사용자 입력성 필드와 `failure_code`/`failure_reason`이 처음부터 별도 컬럼으로 분리돼 있어 실패해도 다른 필드를 덮어쓰지 않는다. `refund`만 두 개념을 한 컬럼에 겸용한다.

## 설계 방향 (초안 — 아직 브레인스토밍 미완료)

**`refund.failure_reason` 컬럼을 신설하고, `reason`은 사용자 환불 사유 전용으로 고정한다.** `Refund.fail()`이 `this.reason`이 아니라 `this.failureReason`에 값을 쓰도록 변경한다. 이렇게 하면 환불이 실패해도 원래 요청 사유가 보존되고, 실패 사유는 별도 필드로 명확히 구분된다 — `payment` 테이블의 `failure_code`/`failure_reason` 분리 패턴과 대칭을 이룬다.

## 설계 방향 2 — `refund.failed_at` 컬럼 추가 (초안)

`Refund`엔 `requestedAt`/`completedAt`은 있지만 `failedAt`이 없어, `fail()` 호출 시점의 실제 실패 시각이 엔티티에 남지 않는다. [#484 감사로그](484-payment-audit-log.md)의 `AuditLogEventListener.onPaymentRefundFailed`는 이 컬럼이 없어 부득이 이벤트 처리 시각(`OffsetDateTime.now()`)을 `occurred_at` 근사치로 쓴다(AFTER_COMMIT 리스너라 실제 실패 시각과 밀리초 단위 오차만 있어 484 스코프에선 허용했음). `KafkaPaymentEventPublisher.onPaymentRefundFailed`도 동일하게 `now()`를 씀.

`Refund.fail()`이 `failedAt` 파라미터를 받아 저장하도록 바꾸면(패턴은 `Payment.fail(..., OffsetDateTime failedAt)`과 동일), 두 리스너 모두 근사치 대신 `refund.getFailedAt()`을 그대로 쓸 수 있다. `RefundService`에서 PG 응답 실패 시각(또는 처리 시각)을 `fail()` 호출부에 넘기도록 수정 필요.

## 필요 작업 (예상)

- `@Entity` 컬럼 추가이므로 `flyway-migration.md` 규칙에 따라 신규 `V{n}` 마이그레이션 동반 필요 (`failure_reason`, `failed_at` 두 컬럼 모두 nullable)
- `Refund.java`: `failureReason` 필드 추가, `fail()` 메서드가 `this.reason` 대신 `this.failureReason`에 값을 쓰도록 수정
- `Refund.java`: `failedAt` 필드 추가, `fail(String reason, OffsetDateTime failedAt)`으로 시그니처 변경
- `db-schema.md`의 `refund` 테이블 섹션 갱신 (`reason` 설명에서 "실패 사유" 문구 제거, `failure_reason`/`failed_at` 행 추가)
- `RefundService.java`의 `refund.fail(...)` 호출부(실패 시각 인자 추가) 및 관련 테스트(`RefundJpaRepositoryTest` 등) 영향 범위 확인
- `PaymentRefundFailedEvent`의 `failureReason` 파라미터가 이제 `refund.getFailureReason()`과 의미상 완전히 대응되는지 재검토(현재는 중복 전달 구조)
- `AuditLogEventListener.onPaymentRefundFailed`(#484 구현분): `occurred_at` 소스를 `OffsetDateTime.now()` 근사치에서 `refund.getFailedAt()`으로 교체
- `KafkaPaymentEventPublisher.onPaymentRefundFailed`: 동일하게 `now()` 대신 `refund.getFailedAt()`으로 교체

## 상태

**#484 감사로그 작업 완료 후 처리 예정.** 정식 GitHub 이슈 번호 발급 전 임시 메모 — 이슈 생성 시 파일명을 `{이슈번호}-refund-reason-split.md`로 이동/변경할 것.
