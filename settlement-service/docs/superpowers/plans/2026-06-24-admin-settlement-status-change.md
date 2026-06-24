# 어드민 정산 상태 변경(Settlement Status Change) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 정산 목록에서 각 정산 건을 승인/보류/지급 등 6종 상태로 전이시키는 PATCH API를 추가한다(취소 제외).

**Architecture:** 헥사고날. 도메인(`Settlement`)이 상태 전이 불변식을 보장하고, 애플리케이션 서비스가 정산 조회 → 도메인 메서드 호출 → 저장을 한 트랜잭션으로 묶는다. **상태 변경이 단순한 명령**이므로 `~Result`를 생략하고 서비스가 `SettlementStatusResponse`를 직접 만들어 반환한다(clean-architecture §1/§7 단순 명령 예외). 컨트롤러는 변환 없이 `ApiResult`로 감싸기만 한다.

**Tech Stack:** Java 17+, Spring Boot, Spring Data JPA, JUnit5 + AssertJ + Mockito, Gradle.

설계 문서: `docs/superpowers/specs/2026-06-24-admin-settlement-status-change-design.md`
이슈: #64

## Global Constraints

- 생성물은 `settlement-service/` 모듈 하위에만 둔다. (CLAUDE.md)
- 도메인 계층은 `ErrorCode`·`HttpStatus`를 import 하지 않는다. 불변식 위반은 **순수 도메인 예외**(`extends RuntimeException`, `domain/exception`)로 던진다. (controller-exception §2-4)
- 엔티티 상태 변경은 setter가 아닌 **도메인 메서드**로만 한다. 메서드 내부에서 전이 가능 여부를 검증한다. (domain-model §8)
- 에러 코드·메시지·HTTP 상태의 단일 출처는 `SettlementErrorCode` enum. 핸들러는 "도메인 예외 → ErrorCode" 매핑만 한다. (controller-exception §2-2/§2-3)
- **단순 상태 변경 명령**은 서비스가 `~Response`를 직접 반환할 수 있다(clean-architecture §1/§7). 단 Swagger 애너테이션(`@Schema` 등)은 presentation `~Response`에만 둔다(swagger §1). application 코드에 Swagger 애너테이션을 달지 않는다.
- 와일드카드 import 금지, 빈 catch 금지. (code-style)
- 모든 신규 엔드포인트에 Swagger 애너테이션(`@Operation`/`@ApiResponses`/`@Parameter`), 신규 DTO·필드에 `@Schema`를 단다. (swagger)
- 컨트롤러 경로는 `${api.init}/admin/settlements` 하위. `/admin/**`는 `AdminAuthorizationInterceptor`로 ADMIN 인증이 자동 적용된다 — 컨트롤러에 인증 로직을 넣지 않는다.
- `confirmedAt`/`paidAt` 시각은 서비스에서 생성해 도메인에 주입한다(테스트 용이성, domain-model §6).
- 테스트 실행은 `settlement-service/` 디렉토리에서 `./gradlew test`.
- 커밋 메시지: `<타입>: <내용>` (feat/test/docs 등). Claude co-author 트레일러를 넣지 않는다.

## cancel 트랙과의 공유 주의

`docs/superpowers/plans/2026-06-24-settlement-cancel.md`(별도 트랙)도 다음을 추가한다. **먼저 머지되는 쪽이 추가하고, 다른 쪽은 재사용**한다(중복 정의 시 컴파일 충돌).

- `SettlementRepository.findById(UUID)` — 양 트랙 공통. 이미 있으면 추가하지 않는다.
- `SettlementErrorCode.SETTLEMENT_NOT_FOUND` — 양 트랙 공통(코드 `S-010`). 이미 있으면 재사용한다.
- cancel은 `S-011`(ALREADY_PAID)·`S-012`(ALREADY_CANCELLED)를 쓰므로, 본 플랜의 `SETTLEMENT_INVALID_STATE`는 `S-013`을 쓴다.

---

## 파일 구조

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `domain/exception/SettlementInvalidStateException.java` | 상태 전이 불가 순수 예외 | Create |
| `domain/model/Settlement.java` | 상태 전이 메서드 6종 | Modify |
| `global/exception/SettlementErrorCode.java` | 에러 코드 2종 추가 | Modify |
| `global/exception/GlobalExceptionHandler.java` | 도메인 예외 매핑 | Modify |
| `presentation/dto/response/SettlementStatusResponse.java` | 통합 상태 변경 응답 DTO | Create |
| `domain/repository/SettlementRepository.java` | `findById` 포트 메서드 | Modify |
| `infrastructure/persistence/SettlementRepositoryAdapter.java` | `findById` 위임 | Modify |
| `application/usecase/SettlementUseCase.java` | 명령 메서드 6종 | Modify |
| `application/service/SettlementApplicationService.java` | 명령 6종 구현 | Modify |
| `presentation/controller/SettlementController.java` | PATCH 엔드포인트 6종 | Modify |

## Task 의존 순서

```
Task 1 (도메인 전이/예외) ─┬─▶ Task 2 (에러코드/핸들러)
                          │
                          └─▶ Task 3 (응답 DTO/포트/서비스 6종) ─▶ Task 4 (컨트롤러)
                                         ▲
                          Task 2 (SETTLEMENT_NOT_FOUND) ┘
```

실행 순서: Task 1 → Task 2 → Task 3 → Task 4.

---

### Task 1: 도메인 — `Settlement` 상태 전이 6종 + `SettlementInvalidStateException`

**Files:**
- Create: `src/main/java/com/prompthub/settlement/domain/exception/SettlementInvalidStateException.java`
- Modify: `src/main/java/com/prompthub/settlement/domain/model/Settlement.java`
- Test: `src/test/java/com/prompthub/settlement/domain/model/SettlementTest.java`

**Interfaces:**
- Consumes: 기존 `Settlement.create(UUID, UUID, YearMonth, List<SettlementDetail>)`, `Settlement.displayStatus()`, `SettlementStatus`, `PayoutStatus`, `SettlementDetail.sale(...)`.
- Produces:
  - `Settlement.approve(LocalDateTime confirmedAt)` → void. `settlementStatus != PENDING_APPROVAL`이면 `SettlementInvalidStateException`. 통과 시 `settlementStatus = APPROVED`, `payoutStatus = READY`, `confirmedAt` 세팅.
  - `Settlement.hold()` → void. `PENDING_APPROVAL`만 허용 → `SETTLEMENT_ON_HOLD`.
  - `Settlement.releaseHold()` → void. `SETTLEMENT_ON_HOLD`만 허용 → `PENDING_APPROVAL`.
  - `Settlement.payout(LocalDateTime paidAt)` → void. `APPROVED && payout=READY`만 허용 → `payout=PAID`, `paidAt` 세팅.
  - `Settlement.payoutHold()` → void. `APPROVED && payout=READY`만 허용 → `payout=PAYOUT_ON_HOLD`.
  - `Settlement.releasePayoutHold()` → void. `APPROVED && payout=PAYOUT_ON_HOLD`만 허용 → `payout=READY`.
  - `SettlementInvalidStateException(String action, SettlementStatus, PayoutStatus)` (`extends RuntimeException`).

- [ ] **Step 1: 실패하는 도메인 테스트 작성**

`SettlementTest.java` 상단 import에 추가:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import org.springframework.test.util.ReflectionTestUtils;
```

클래스 본문 끝(마지막 `}` 앞)에 헬퍼와 테스트 추가. `settlementStatus`/`payoutStatus`는 setter가 없으므로 선행 상태는 `ReflectionTestUtils`로 만든다(테스트 전용):

```java
    private Settlement pendingSettlement() {
        return Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 6),
                List.of(detail("100.00", "0.15")));
    }

    @Test
    @DisplayName("승인: PENDING_APPROVAL 정산을 승인하면 APPROVED + payout READY로 전이하고 confirmedAt을 기록한다")
    void approve_fromPending() {
        Settlement settlement = pendingSettlement();
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 6, 24, 9, 0);

        settlement.approve(confirmedAt);

        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.APPROVED);
        assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.READY);
        assertThat(settlement.getConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
    }

    @Test
    @DisplayName("승인: PENDING_APPROVAL이 아니면 예외를 던진다")
    void approve_whenNotPending_throws() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);

        assertThatThrownBy(() -> settlement.approve(LocalDateTime.of(2026, 6, 24, 9, 0)))
                .isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("승인 보류: PENDING_APPROVAL 정산을 보류하면 SETTLEMENT_ON_HOLD로 전이한다")
    void hold_fromPending() {
        Settlement settlement = pendingSettlement();

        settlement.hold();

        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLEMENT_ON_HOLD);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVAL_ON_HOLD);
    }

    @Test
    @DisplayName("승인 보류: PENDING_APPROVAL이 아니면 예외를 던진다")
    void hold_whenNotPending_throws() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);

        assertThatThrownBy(settlement::hold).isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("승인 보류 해제: SETTLEMENT_ON_HOLD 정산을 해제하면 PENDING_APPROVAL로 전이한다")
    void releaseHold_fromOnHold() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.SETTLEMENT_ON_HOLD);

        settlement.releaseHold();

        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING_APPROVAL);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.WAITING);
    }

    @Test
    @DisplayName("승인 보류 해제: SETTLEMENT_ON_HOLD가 아니면 예외를 던진다")
    void releaseHold_whenNotOnHold_throws() {
        Settlement settlement = pendingSettlement();

        assertThatThrownBy(settlement::releaseHold).isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("지급: APPROVED & READY 정산을 지급하면 payout PAID로 전이하고 paidAt을 기록한다")
    void payout_fromApprovedReady() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(settlement, "payoutStatus", PayoutStatus.READY);
        LocalDateTime paidAt = LocalDateTime.of(2026, 6, 24, 15, 0);

        settlement.payout(paidAt);

        assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(settlement.getPaidAt()).isEqualTo(paidAt);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.PAID);
    }

    @Test
    @DisplayName("지급: APPROVED & READY가 아니면 예외를 던진다")
    void payout_whenNotApprovedReady_throws() {
        Settlement settlement = pendingSettlement();

        assertThatThrownBy(() -> settlement.payout(LocalDateTime.of(2026, 6, 24, 15, 0)))
                .isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("지급 보류: APPROVED & READY 정산을 보류하면 payout PAYOUT_ON_HOLD로 전이한다")
    void payoutHold_fromApprovedReady() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(settlement, "payoutStatus", PayoutStatus.READY);

        settlement.payoutHold();

        assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.PAYOUT_ON_HOLD);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.PAYOUT_ON_HOLD);
    }

    @Test
    @DisplayName("지급 보류: APPROVED & READY가 아니면 예외를 던진다")
    void payoutHold_whenNotApprovedReady_throws() {
        Settlement settlement = pendingSettlement();

        assertThatThrownBy(settlement::payoutHold).isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("지급 보류 해제: APPROVED & PAYOUT_ON_HOLD 정산을 해제하면 payout READY로 전이한다")
    void releasePayoutHold_fromApprovedOnHold() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(settlement, "payoutStatus", PayoutStatus.PAYOUT_ON_HOLD);

        settlement.releasePayoutHold();

        assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.READY);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
    }

    @Test
    @DisplayName("지급 보류 해제: APPROVED & PAYOUT_ON_HOLD가 아니면 예외를 던진다")
    void releasePayoutHold_whenNotApprovedOnHold_throws() {
        Settlement settlement = pendingSettlement();

        assertThatThrownBy(settlement::releasePayoutHold).isInstanceOf(SettlementInvalidStateException.class);
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.domain.model.SettlementTest"`
Expected: 컴파일 에러(`approve`/`hold`/… 메서드, `SettlementInvalidStateException` 미존재).

- [ ] **Step 3: 순수 도메인 예외 작성**

`SettlementInvalidStateException.java`:

```java
package com.prompthub.settlement.domain.exception;

import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;

public class SettlementInvalidStateException extends RuntimeException {

    public SettlementInvalidStateException(String action, SettlementStatus settlementStatus,
                                           PayoutStatus payoutStatus) {
        super("정산 상태 전이 불가: action=" + action
                + ", settlementStatus=" + settlementStatus
                + ", payoutStatus=" + payoutStatus);
    }
}
```

- [ ] **Step 4: `Settlement`에 상태 전이 메서드 6종 추가**

import 추가:

```java
import com.prompthub.settlement.domain.exception.SettlementInvalidStateException;
```

`displayStatus()` 메서드 바로 위에 6종 추가:

```java
    public void approve(LocalDateTime confirmedAt) {
        if (this.settlementStatus != SettlementStatus.PENDING_APPROVAL) {
            throw new SettlementInvalidStateException("approve", this.settlementStatus, this.payoutStatus);
        }
        this.settlementStatus = SettlementStatus.APPROVED;
        this.payoutStatus = PayoutStatus.READY;
        this.confirmedAt = confirmedAt;
    }

    public void hold() {
        if (this.settlementStatus != SettlementStatus.PENDING_APPROVAL) {
            throw new SettlementInvalidStateException("hold", this.settlementStatus, this.payoutStatus);
        }
        this.settlementStatus = SettlementStatus.SETTLEMENT_ON_HOLD;
    }

    public void releaseHold() {
        if (this.settlementStatus != SettlementStatus.SETTLEMENT_ON_HOLD) {
            throw new SettlementInvalidStateException("releaseHold", this.settlementStatus, this.payoutStatus);
        }
        this.settlementStatus = SettlementStatus.PENDING_APPROVAL;
    }

    public void payout(LocalDateTime paidAt) {
        if (this.settlementStatus != SettlementStatus.APPROVED || this.payoutStatus != PayoutStatus.READY) {
            throw new SettlementInvalidStateException("payout", this.settlementStatus, this.payoutStatus);
        }
        this.payoutStatus = PayoutStatus.PAID;
        this.paidAt = paidAt;
    }

    public void payoutHold() {
        if (this.settlementStatus != SettlementStatus.APPROVED || this.payoutStatus != PayoutStatus.READY) {
            throw new SettlementInvalidStateException("payoutHold", this.settlementStatus, this.payoutStatus);
        }
        this.payoutStatus = PayoutStatus.PAYOUT_ON_HOLD;
    }

    public void releasePayoutHold() {
        if (this.settlementStatus != SettlementStatus.APPROVED
                || this.payoutStatus != PayoutStatus.PAYOUT_ON_HOLD) {
            throw new SettlementInvalidStateException("releasePayoutHold", this.settlementStatus, this.payoutStatus);
        }
        this.payoutStatus = PayoutStatus.READY;
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.domain.model.SettlementTest"`
Expected: PASS (기존 4개 + 신규 12개).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/prompthub/settlement/domain/exception/SettlementInvalidStateException.java \
        src/main/java/com/prompthub/settlement/domain/model/Settlement.java \
        src/test/java/com/prompthub/settlement/domain/model/SettlementTest.java
git commit -m "feat: 정산 상태 전이 도메인 메서드와 순수 예외 추가"
```

---

### Task 2: 예외 매핑 — `SettlementErrorCode` + `GlobalExceptionHandler`

**Files:**
- Modify: `src/main/java/com/prompthub/settlement/global/exception/SettlementErrorCode.java`
- Modify: `src/main/java/com/prompthub/settlement/global/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: `SettlementInvalidStateException`(Task 1), 기존 `ErrorResponse.of(ErrorCode)`, `ErrorCode`.
- Produces: `SettlementErrorCode.SETTLEMENT_NOT_FOUND`(404, `S-010`), `SettlementErrorCode.SETTLEMENT_INVALID_STATE`(409, `S-013`), `SettlementInvalidStateException → 409` 핸들러.

> 단순한 매핑이라 단위 테스트 대신 전체 빌드로 검증한다. cancel 트랙이 먼저 머지돼 `SETTLEMENT_NOT_FOUND`가 이미 있으면 그 줄은 추가하지 말고 재사용한다.

- [ ] **Step 1: 에러 코드 2종 추가**

`SettlementErrorCode.java` — 마지막 상수 `SETTLEMENT_SOURCE_LINE_ALREADY_SETTLED(...)`의 끝 `;`를 `,`로 바꾸고 두 줄 추가:

```java
	SETTLEMENT_SOURCE_LINE_ALREADY_SETTLED("S-009", "이미 정산에 포함된 소스 라인입니다.", HttpStatus.CONFLICT),
	SETTLEMENT_NOT_FOUND("S-010", "정산을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	SETTLEMENT_INVALID_STATE("S-013", "현재 상태에서 변경할 수 없는 정산입니다.", HttpStatus.CONFLICT);
```

- [ ] **Step 2: 핸들러 매핑 추가**

`GlobalExceptionHandler.java` — import 추가:

```java
import com.prompthub.settlement.domain.exception.SettlementInvalidStateException;
```

기존 `handleSettlementSourceLineAlreadySettled` 핸들러 아래에 추가:

```java
    @ExceptionHandler(SettlementInvalidStateException.class)
    public ResponseEntity<ErrorResponse> handleSettlementInvalidState(
            SettlementInvalidStateException exception) {
        log.warn("정산 상태 전이 충돌 - {}", exception.getMessage());
        ErrorCode errorCode = SettlementErrorCode.SETTLEMENT_INVALID_STATE;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }
```

- [ ] **Step 3: 전체 빌드 확인**

Run: `./gradlew test`
Expected: 전체 PASS (컴파일 + 기존 테스트 통과).

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/prompthub/settlement/global/exception/SettlementErrorCode.java \
        src/main/java/com/prompthub/settlement/global/exception/GlobalExceptionHandler.java
git commit -m "feat: 정산 상태 전이 예외를 ErrorCode로 매핑"
```

---

### Task 3: 응답 DTO + 포트/어댑터 + 애플리케이션 6종

**Files:**
- Create: `src/main/java/com/prompthub/settlement/presentation/dto/response/SettlementStatusResponse.java`
- Modify: `src/main/java/com/prompthub/settlement/domain/repository/SettlementRepository.java`
- Modify: `src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementRepositoryAdapter.java`
- Modify: `src/main/java/com/prompthub/settlement/application/usecase/SettlementUseCase.java`
- Modify: `src/main/java/com/prompthub/settlement/application/service/SettlementApplicationService.java`
- Test: `src/test/java/com/prompthub/settlement/application/service/SettlementApplicationServiceTest.java`

**Interfaces:**
- Consumes:
  - `Settlement.approve/hold/releaseHold/payout/payoutHold/releasePayoutHold`(Task 1)
  - `Settlement` getter: `getId`, `getSettlementStatus`, `getPayoutStatus`, `displayStatus`, `getConfirmedAt`, `getPaidAt`, `getPayoutReference`, `getFailedReason`, `getUpdatedAt`
  - `SettlementErrorCode.SETTLEMENT_NOT_FOUND`(Task 2), 기존 `SettlementException(ErrorCode)`
- Produces:
  - `SettlementStatusResponse`(record) + `SettlementStatusResponse.from(Settlement)` → `SettlementStatusResponse`
  - `SettlementRepository.findById(UUID)` → `Optional<Settlement>`
  - `SettlementUseCase.approve/hold/releaseHold/payout/payoutHold/releasePayoutHold(UUID)` → `SettlementStatusResponse`(각각)
  - `SettlementApplicationService` 6종 구현(`@Transactional`)

- [ ] **Step 1: 응답 DTO 작성**

`SettlementStatusResponse.java`:

```java
package com.prompthub.settlement.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "정산 상태 변경 응답")
public record SettlementStatusResponse(
        @Schema(description = "정산 ID(UUID)")
        UUID settlementId,

        @Schema(description = "정산 상태", example = "APPROVED")
        SettlementStatus settlementStatus,

        @Schema(description = "지급 상태", example = "READY")
        PayoutStatus payoutStatus,

        @Schema(description = "정산 표시 상태", example = "APPROVED")
        SettlementDisplayStatus displayStatus,

        @Schema(description = "승인 시각", example = "2026-06-24T09:00:00")
        LocalDateTime confirmedAt,

        @Schema(description = "지급 완료 시각", example = "2026-06-24T15:00:00")
        LocalDateTime paidAt,

        @Schema(description = "지급 참조 번호")
        String payoutReference,

        @Schema(description = "정산 실패 사유")
        String failureReason,

        @Schema(description = "최종 수정 시각", example = "2026-06-24T09:00:00")
        LocalDateTime updatedAt
) {

    public static SettlementStatusResponse from(Settlement settlement) {
        return new SettlementStatusResponse(
                settlement.getId(),
                settlement.getSettlementStatus(),
                settlement.getPayoutStatus(),
                settlement.displayStatus(),
                settlement.getConfirmedAt(),
                settlement.getPaidAt(),
                settlement.getPayoutReference(),
                settlement.getFailedReason(),
                settlement.getUpdatedAt());
    }
}
```

- [ ] **Step 2: 실패하는 서비스 테스트 작성**

`SettlementApplicationServiceTest.java`는 현재 `SettlementQueryRepository` 하나만 `@Mock`으로 갖는다. 6종 명령은 `SettlementRepository`를 쓰므로 mock을 추가한다. 클래스 상단 `@Mock` 필드에 추가:

```java
    @Mock
    private SettlementRepository settlementRepository;
```

import 추가:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;

import com.prompthub.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.global.exception.SettlementException;
import com.prompthub.settlement.presentation.dto.response.SettlementStatusResponse;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;
```

클래스 본문 끝에 테스트 추가. 선행 상태는 `ReflectionTestUtils`로 세팅하고, 응답의 `settlementId`를 검증할 땐 id도 세팅한다(저장 전 엔티티는 id가 null):

```java
    @Test
    @DisplayName("승인: 정산을 APPROVED/READY로 바꾸고 변경된 상태를 응답으로 반환한다")
    void approve_returnsApprovedResponse() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "id", settlementId);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.approve(settlementId);

        assertThat(response.settlementId()).isEqualTo(settlementId);
        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.APPROVED);
        assertThat(response.payoutStatus()).isEqualTo(PayoutStatus.READY);
        assertThat(response.confirmedAt()).isNotNull();
        assertThat(response.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
        then(settlementRepository).should().save(target);
    }

    @Test
    @DisplayName("승인: 정산이 없으면 SettlementException을 던진다")
    void approve_notFound_throws() {
        UUID settlementId = UUID.randomUUID();
        given(settlementRepository.findById(settlementId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementApplicationService.approve(settlementId))
                .isInstanceOf(SettlementException.class);
    }

    @Test
    @DisplayName("승인: 잘못된 상태면 도메인 예외가 전파된다")
    void approve_invalidState_propagates() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.APPROVED);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        assertThatThrownBy(() -> settlementApplicationService.approve(settlementId))
                .isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("승인 보류: SETTLEMENT_ON_HOLD 응답을 반환한다")
    void hold_returnsOnHold() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.hold(settlementId);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.SETTLEMENT_ON_HOLD);
        assertThat(response.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVAL_ON_HOLD);
    }

    @Test
    @DisplayName("승인 보류 해제: PENDING_APPROVAL 응답을 반환한다")
    void releaseHold_returnsPending() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.SETTLEMENT_ON_HOLD);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.releaseHold(settlementId);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("지급: payout PAID 응답을 반환한다")
    void payout_returnsPaid() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(target, "payoutStatus", PayoutStatus.READY);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.payout(settlementId);

        assertThat(response.payoutStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(response.paidAt()).isNotNull();
        assertThat(response.displayStatus()).isEqualTo(SettlementDisplayStatus.PAID);
    }

    @Test
    @DisplayName("지급 보류: payout PAYOUT_ON_HOLD 응답을 반환한다")
    void payoutHold_returnsOnHold() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(target, "payoutStatus", PayoutStatus.READY);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.payoutHold(settlementId);

        assertThat(response.payoutStatus()).isEqualTo(PayoutStatus.PAYOUT_ON_HOLD);
    }

    @Test
    @DisplayName("지급 보류 해제: payout READY 응답을 반환한다")
    void releasePayoutHold_returnsReady() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(target, "payoutStatus", PayoutStatus.PAYOUT_ON_HOLD);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.releasePayoutHold(settlementId);

        assertThat(response.payoutStatus()).isEqualTo(PayoutStatus.READY);
    }
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.application.service.SettlementApplicationServiceTest"`
Expected: 컴파일 에러(`findById`, 6종 서비스 메서드 미존재).

- [ ] **Step 4: 포트에 `findById` 추가**

`SettlementRepository.java` — import `java.util.Optional;` 추가하고 메서드 추가:

```java
    Optional<Settlement> findById(UUID id);
```

- [ ] **Step 5: 어댑터 위임 구현**

`SettlementRepositoryAdapter.java` — import `java.util.Optional;` 추가하고 메서드 추가:

```java
    @Override
    public Optional<Settlement> findById(UUID id) {
        return jpaRepository.findById(id);
    }
```

(`SettlementJpaRepository`는 `JpaRepository<Settlement, UUID>`라 `findById`를 이미 제공하므로 별도 선언 불필요.)

- [ ] **Step 6: 인바운드 포트에 6종 추가**

`SettlementUseCase.java` — import 추가:

```java
import com.prompthub.settlement.presentation.dto.response.SettlementStatusResponse;
import java.util.UUID;
```

인터페이스 본문에 메서드 추가:

```java
    SettlementStatusResponse approve(UUID settlementId);

    SettlementStatusResponse hold(UUID settlementId);

    SettlementStatusResponse releaseHold(UUID settlementId);

    SettlementStatusResponse payout(UUID settlementId);

    SettlementStatusResponse payoutHold(UUID settlementId);

    SettlementStatusResponse releasePayoutHold(UUID settlementId);
```

- [ ] **Step 7: 서비스에 6종 구현**

`SettlementApplicationService.java`는 클래스에 `@Transactional(readOnly = true)`가 걸려 있다. 명령 6종은 쓰기이므로 **메서드에 `@Transactional`을 붙여 오버라이드**한다.

import 추가:

```java
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import com.prompthub.settlement.presentation.dto.response.SettlementStatusResponse;
import java.time.LocalDateTime;
import java.util.UUID;
```

`@RequiredArgsConstructor`이므로 final 필드만 추가하면 생성자가 자동 반영된다. 기존 `settlementQueryRepository` 필드 아래에 추가:

```java
    private final SettlementRepository settlementRepository;
```

클래스 본문(기존 `getList` 아래)에 메서드 추가:

```java
    @Override
    @Transactional
    public SettlementStatusResponse approve(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.approve(LocalDateTime.now());
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse hold(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.hold();
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse releaseHold(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.releaseHold();
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse payout(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.payout(LocalDateTime.now());
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse payoutHold(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.payoutHold();
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse releasePayoutHold(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.releasePayoutHold();
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    private Settlement findSettlement(UUID settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new SettlementException(SettlementErrorCode.SETTLEMENT_NOT_FOUND));
    }
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.application.service.SettlementApplicationServiceTest"`
Expected: PASS (기존 5개 + 신규 8개).

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/prompthub/settlement/presentation/dto/response/SettlementStatusResponse.java \
        src/main/java/com/prompthub/settlement/domain/repository/SettlementRepository.java \
        src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementRepositoryAdapter.java \
        src/main/java/com/prompthub/settlement/application/usecase/SettlementUseCase.java \
        src/main/java/com/prompthub/settlement/application/service/SettlementApplicationService.java \
        src/test/java/com/prompthub/settlement/application/service/SettlementApplicationServiceTest.java
git commit -m "feat: 정산 상태 변경 유스케이스와 응답 DTO 구현"
```

---

### Task 4: 표현 — `SettlementController` PATCH 6종

**Files:**
- Modify: `src/main/java/com/prompthub/settlement/presentation/controller/SettlementController.java`

**Interfaces:**
- Consumes: `SettlementUseCase.approve/hold/releaseHold/payout/payoutHold/releasePayoutHold(UUID)` → `SettlementStatusResponse`(Task 3), `ApiResult.success(T)`.
- Produces: `PATCH {api.init}/admin/settlements/{settlementId}/{approve|hold|release-hold|payout|payout-hold|payout-hold/release}` → `ApiResult<SettlementStatusResponse>`(200).

> 컨트롤러는 cancel 트랙과 동일하게 단위 테스트 없이 전체 빌드로 검증한다. `actorId`(`X-User-Id`)는 감사 로깅 용도로만 받는다 — 서비스 시그니처는 `settlementId`만 유지한다.

- [ ] **Step 1: 컨트롤러에 PATCH 6종 추가**

`SettlementController.java` — import 추가:

```java
import com.prompthub.settlement.presentation.dto.response.SettlementStatusResponse;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
```

클래스 선언에 `@Slf4j` 추가(`@RestController` 위):

```java
@Slf4j
@RestController
@RequestMapping("${api.init}/admin/settlements")
@RequiredArgsConstructor
@Tag(name = "Settlement", description = "정산 조회 API(관리자)")
public class SettlementController {
```

`getList` 메서드 아래에 6종 핸들러 추가:

```java
    @PatchMapping("/{settlementId}/approve")
    @Operation(summary = "정산 승인",
            description = "승인 대기 상태의 정산을 승인 완료(APPROVED)로 전환합니다. ADMIN 권한이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 성공",
                    content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "정산 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "전이 불가 상태",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementStatusResponse> approve(
            @Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
            @Parameter(description = "요청 수행자 ID(UUID)") @RequestHeader("X-User-Id") UUID actorId) {
        log.info("정산 승인 요청 - settlementId={}, actorId={}", settlementId, actorId);
        return ApiResult.success(settlementUseCase.approve(settlementId));
    }

    @PatchMapping("/{settlementId}/hold")
    @Operation(summary = "정산 승인 보류",
            description = "승인 대기 상태의 정산을 승인 보류(SETTLEMENT_ON_HOLD)로 전환합니다. ADMIN 권한이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "보류 성공",
                    content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "정산 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "전이 불가 상태",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementStatusResponse> hold(
            @Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
            @Parameter(description = "요청 수행자 ID(UUID)") @RequestHeader("X-User-Id") UUID actorId) {
        log.info("정산 승인 보류 요청 - settlementId={}, actorId={}", settlementId, actorId);
        return ApiResult.success(settlementUseCase.hold(settlementId));
    }

    @PatchMapping("/{settlementId}/release-hold")
    @Operation(summary = "정산 승인 보류 해제",
            description = "승인 보류 상태의 정산을 승인 대기(PENDING_APPROVAL)로 되돌립니다. ADMIN 권한이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "보류 해제 성공",
                    content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "정산 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "전이 불가 상태",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementStatusResponse> releaseHold(
            @Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
            @Parameter(description = "요청 수행자 ID(UUID)") @RequestHeader("X-User-Id") UUID actorId) {
        log.info("정산 승인 보류 해제 요청 - settlementId={}, actorId={}", settlementId, actorId);
        return ApiResult.success(settlementUseCase.releaseHold(settlementId));
    }

    @PatchMapping("/{settlementId}/payout")
    @Operation(summary = "정산 지급",
            description = "지급 준비(READY)된 정산을 지급 완료(PAID)로 전환합니다. ADMIN 권한이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "지급 성공",
                    content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "정산 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "전이 불가 상태",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementStatusResponse> payout(
            @Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
            @Parameter(description = "요청 수행자 ID(UUID)") @RequestHeader("X-User-Id") UUID actorId) {
        log.info("정산 지급 요청 - settlementId={}, actorId={}", settlementId, actorId);
        return ApiResult.success(settlementUseCase.payout(settlementId));
    }

    @PatchMapping("/{settlementId}/payout-hold")
    @Operation(summary = "정산 지급 보류",
            description = "지급 준비(READY)된 정산을 지급 보류(PAYOUT_ON_HOLD)로 전환합니다. ADMIN 권한이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "지급 보류 성공",
                    content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "정산 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "전이 불가 상태",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementStatusResponse> payoutHold(
            @Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
            @Parameter(description = "요청 수행자 ID(UUID)") @RequestHeader("X-User-Id") UUID actorId) {
        log.info("정산 지급 보류 요청 - settlementId={}, actorId={}", settlementId, actorId);
        return ApiResult.success(settlementUseCase.payoutHold(settlementId));
    }

    @PatchMapping("/{settlementId}/payout-hold/release")
    @Operation(summary = "정산 지급 보류 해제",
            description = "지급 보류(PAYOUT_ON_HOLD)된 정산을 지급 준비(READY)로 되돌립니다. ADMIN 권한이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "지급 보류 해제 성공",
                    content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "정산 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "전이 불가 상태",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementStatusResponse> releasePayoutHold(
            @Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
            @Parameter(description = "요청 수행자 ID(UUID)") @RequestHeader("X-User-Id") UUID actorId) {
        log.info("정산 지급 보류 해제 요청 - settlementId={}, actorId={}", settlementId, actorId);
        return ApiResult.success(settlementUseCase.releasePayoutHold(settlementId));
    }
```

- [ ] **Step 2: 전체 빌드·테스트 확인**

Run: `./gradlew test`
Expected: 전체 PASS.

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/prompthub/settlement/presentation/controller/SettlementController.java
git commit -m "feat: 정산 상태 변경 API 엔드포인트 추가"
```

---

## 검증 (전체)

- [ ] `./gradlew test` 전체 통과
- [ ] (선택) 앱 실행 후 각 PATCH 호출 → 200 + 기대 상태/displayStatus 확인, 잘못된 상태에서 호출 → 409, 없는 id → 404
- [ ] `verify-rules` 스킬로 룰 7종 검증 후 PR (룰 문서 변경분 포함 — 단순 명령 흐름 서비스 `~Response` 반환 허용)

## 비범위 (YAGNI)

- 정산 취소(cancel) — 별도 이슈/세션
- `payoutReference` 생성 / 외부 송금 연동
- actorId 영속화(감사 로그 테이블)
- 보류·취소 사유 입력
- 멱등 처리(같은 상태로의 재요청은 선행 상태 검증에서 409로 거부됨)

## 참고: 컨벤션 메모

- 명령 흐름이지만 **상태 변경이 단순**(입력 식별자뿐, 산출은 변경된 단일 엔티티 상태)하므로 `~Result`를 생략하고 서비스가 `SettlementStatusResponse`를 직접 반환한다. 이 예외는 이번 작업에서 `clean-architecture §1/§7`·`controller-exception §1`·`swagger §1`에 명문화했다.
- `ReflectionTestUtils`로 상태/`id`를 세팅하는 것은 **테스트 코드 한정**이다. 운영 코드에는 setter를 추가하지 않는다.
