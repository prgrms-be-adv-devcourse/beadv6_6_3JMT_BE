# 정산 취소(Settlement Cancel) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 지급 완료(PAID) 전 정산을 취소하면 `CANCELLED`로 남기고, 묶인 소스 라인의 `settlementId`를 풀어 다음 정산 실행에서 재계산되게 한다.

**Architecture:** 헥사고날. 도메인(`Settlement.cancel`, `SettlementSourceLine.release`)이 불변식을 보장하고, 애플리케이션 서비스가 정산 조회 → 취소 → 소스 라인 복귀를 한 트랜잭션으로 묶는다. 컨트롤러는 얇게 받아 `SettlementResponse`로 변환한다. 즉시 재계산은 하지 않고, 풀린 라인을 기존 스케줄러/수동 정산이 `settlementId is null` 조건으로 다시 주워간다.

**Tech Stack:** Java 17+, Spring Boot, Spring Data JPA, JUnit5 + AssertJ + Mockito, Gradle.

설계 문서: `docs/superpowers/specs/2026-06-24-settlement-cancel-design.md`

## Global Constraints

- 생성물은 `settlement-service/` 모듈 하위에만 둔다. (CLAUDE.md)
- 도메인 계층은 `ErrorCode`·`HttpStatus`를 import 하지 않는다. 불변식 위반은 **순수 도메인 예외**(`extends RuntimeException`, `domain/exception`)로 던진다. (controller-exception §2-4)
- 엔티티 상태 변경은 setter가 아닌 **도메인 메서드**로만 한다. 메서드 내부에서 전이 가능 여부를 검증한다. (domain-model §8)
- 에러 코드·메시지·HTTP 상태의 단일 출처는 `SettlementErrorCode` enum. 핸들러는 "도메인 예외 → ErrorCode" 매핑만 한다. (controller-exception §2-2/§2-3)
- 와일드카드 import 금지, 빈 catch 금지. (code-style)
- 모든 신규 엔드포인트·DTO·필드에 Swagger 애너테이션(`@Operation`/`@ApiResponses`/`@Schema`)을 단다. Swagger 애너테이션은 presentation 계층에만 둔다. (swagger)
- 컨트롤러 경로는 `${api.init}/admin/settlements` 하위. `/admin/**`는 `WebConfig`의 `AdminAuthorizationInterceptor`로 ADMIN 인증이 자동 적용된다 — 컨트롤러에 인증 로직을 넣지 않는다.
- 테스트 실행은 `settlement-service/` 디렉토리에서 `./gradlew test`.
- 커밋 메시지: `<타입>: <내용>` (feat/test/docs 등). Claude co-author 트레일러를 넣지 않는다.

---

## 파일 구조

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `domain/exception/SettlementAlreadyPaidException.java` | 지급 완료 건 취소 불가 순수 예외 | Create |
| `domain/exception/SettlementAlreadyCancelledException.java` | 이미 취소된 건 순수 예외 | Create |
| `domain/model/Settlement.java` | `canceledAt` 필드 + `cancel()` 도메인 메서드 | Modify |
| `domain/model/SettlementSourceLine.java` | `release()` 도메인 메서드 | Modify |
| `domain/repository/SettlementRepository.java` | `findById` 포트 메서드 | Modify |
| `domain/repository/SettlementSourceRepository.java` | `findBySettlementId` 포트 메서드 | Modify |
| `infrastructure/persistence/SettlementRepositoryAdapter.java` | `findById` 위임 | Modify |
| `infrastructure/persistence/SettlementSourceRepositoryAdapter.java` | `findBySettlementId` 위임 | Modify |
| `infrastructure/persistence/SettlementSourceLineJpaRepository.java` | `findBySettlementId` 파생 쿼리 | Modify |
| `application/usecase/SettlementUseCase.java` | `cancel` 인바운드 포트 메서드 | Modify |
| `application/service/SettlementApplicationService.java` | `cancel` 구현 | Modify |
| `global/exception/SettlementErrorCode.java` | 에러 코드 3종 추가 | Modify |
| `global/exception/GlobalExceptionHandler.java` | 도메인 예외 2종 매핑 | Modify |
| `presentation/dto/response/SettlementResponse.java` | 단건 취소 응답 DTO | Create |
| `presentation/controller/SettlementController.java` | 취소 엔드포인트 | Modify |

## Task 의존 순서

```
Task 1 (도메인 cancel/예외/필드) ─┐
Task 2 (소스라인 release) ────────┼─▶ Task 3 (포트/어댑터 + 서비스 cancel) ─▶ Task 5 (컨트롤러/DTO)
                                  │
Task 4 (에러코드/핸들러) ◀── Task 1 예외
```

---

### Task 1: 도메인 — `Settlement.cancel()` + `canceledAt` + 순수 예외 2종

**Files:**
- Create: `src/main/java/com/prompthub/settlement/domain/exception/SettlementAlreadyPaidException.java`
- Create: `src/main/java/com/prompthub/settlement/domain/exception/SettlementAlreadyCancelledException.java`
- Modify: `src/main/java/com/prompthub/settlement/domain/model/Settlement.java`
- Test: `src/test/java/com/prompthub/settlement/domain/model/SettlementTest.java`

**Interfaces:**
- Consumes: 기존 `Settlement.create(UUID, UUID, YearMonth, List<SettlementDetail>)`, `SettlementStatus`, `PayoutStatus`.
- Produces:
  - `Settlement.cancel(LocalDateTime canceledAt)` → void. `payoutStatus == PAID`면 `SettlementAlreadyPaidException`, `settlementStatus == CANCELLED`면 `SettlementAlreadyCancelledException`. 통과 시 `settlementStatus = CANCELLED`, `canceledAt` 세팅.
  - `Settlement.getCanceledAt()` → `LocalDateTime` (Lombok `@Getter`).
  - `SettlementAlreadyPaidException(UUID settlementId)`, `SettlementAlreadyCancelledException(UUID settlementId)` (둘 다 `extends RuntimeException`).

- [ ] **Step 1: 실패하는 테스트 작성**

`SettlementTest.java`에 import와 테스트를 추가한다. 파일 상단 import에 다음을 추가:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.settlement.domain.exception.SettlementAlreadyCancelledException;
import com.prompthub.settlement.domain.exception.SettlementAlreadyPaidException;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import org.springframework.test.util.ReflectionTestUtils;
```

클래스 본문 끝(마지막 `}` 앞)에 테스트 추가. `payoutStatus`/`settlementStatus`는 setter가 없으므로 `ReflectionTestUtils`로 상태를 만들어 검증한다(테스트 전용):

```java
    private Settlement pendingSettlement() {
        return Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 6),
                List.of(detail("100.00", "0.15")));
    }

    @Test
    @DisplayName("취소: PENDING_APPROVAL 정산을 취소하면 CANCELLED로 전이하고 canceledAt을 기록한다")
    void cancel_fromPending_setsCancelled() {
        Settlement settlement = pendingSettlement();
        LocalDateTime canceledAt = LocalDateTime.of(2026, 6, 24, 9, 0);

        settlement.cancel(canceledAt);

        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.CANCELLED);
        assertThat(settlement.getCanceledAt()).isEqualTo(canceledAt);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.CANCELLED);
    }

    @Test
    @DisplayName("취소: APPROVED 정산도 PAID 전이면 취소할 수 있다")
    void cancel_fromApproved_setsCancelled() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(settlement, "payoutStatus", PayoutStatus.PAYOUT_ON_HOLD);

        settlement.cancel(LocalDateTime.of(2026, 6, 24, 9, 0));

        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.CANCELLED);
    }

    @Test
    @DisplayName("취소: 이미 지급 완료(PAID)된 정산은 취소할 수 없다")
    void cancel_whenPaid_throws() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(settlement, "payoutStatus", PayoutStatus.PAID);

        assertThatThrownBy(() -> settlement.cancel(LocalDateTime.of(2026, 6, 24, 9, 0)))
                .isInstanceOf(SettlementAlreadyPaidException.class);
    }

    @Test
    @DisplayName("취소: 이미 취소된 정산을 다시 취소하면 예외를 던진다")
    void cancel_whenAlreadyCancelled_throws() {
        Settlement settlement = pendingSettlement();
        settlement.cancel(LocalDateTime.of(2026, 6, 24, 9, 0));

        assertThatThrownBy(() -> settlement.cancel(LocalDateTime.of(2026, 6, 24, 10, 0)))
                .isInstanceOf(SettlementAlreadyCancelledException.class);
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.domain.model.SettlementTest"`
Expected: 컴파일 에러(`cancel`, 예외 클래스, `getCanceledAt` 미존재).

- [ ] **Step 3: 순수 도메인 예외 2종 작성**

`SettlementAlreadyPaidException.java`:

```java
package com.prompthub.settlement.domain.exception;

import java.util.UUID;

public class SettlementAlreadyPaidException extends RuntimeException {

    public SettlementAlreadyPaidException(UUID settlementId) {
        super("이미 지급 완료된 정산은 취소할 수 없습니다. settlementId=" + settlementId);
    }
}
```

`SettlementAlreadyCancelledException.java`:

```java
package com.prompthub.settlement.domain.exception;

import java.util.UUID;

public class SettlementAlreadyCancelledException extends RuntimeException {

    public SettlementAlreadyCancelledException(UUID settlementId) {
        super("이미 취소된 정산입니다. settlementId=" + settlementId);
    }
}
```

- [ ] **Step 4: `Settlement`에 `canceledAt` 필드 + `cancel()` 추가**

import 추가(이미 있는 것은 생략):

```java
import com.prompthub.settlement.domain.exception.SettlementAlreadyCancelledException;
import com.prompthub.settlement.domain.exception.SettlementAlreadyPaidException;
```

`paidAt` 필드 선언 바로 아래에 `canceledAt` 필드 추가:

```java
    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;
```

`displayStatus()` 메서드 바로 위(또는 아래)에 `cancel()` 추가:

```java
    public void cancel(LocalDateTime canceledAt) {
        if (this.payoutStatus == PayoutStatus.PAID) {
            throw new SettlementAlreadyPaidException(this.id);
        }
        if (this.settlementStatus == SettlementStatus.CANCELLED) {
            throw new SettlementAlreadyCancelledException(this.id);
        }
        this.settlementStatus = SettlementStatus.CANCELLED;
        this.canceledAt = canceledAt;
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.domain.model.SettlementTest"`
Expected: PASS (기존 4개 + 신규 4개).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/prompthub/settlement/domain/exception/SettlementAlreadyPaidException.java \
        src/main/java/com/prompthub/settlement/domain/exception/SettlementAlreadyCancelledException.java \
        src/main/java/com/prompthub/settlement/domain/model/Settlement.java \
        src/test/java/com/prompthub/settlement/domain/model/SettlementTest.java
git commit -m "feat: 정산 취소 도메인 메서드와 순수 예외 추가"
```

---

### Task 2: 도메인 — `SettlementSourceLine.release()`

**Files:**
- Modify: `src/main/java/com/prompthub/settlement/domain/model/SettlementSourceLine.java`
- Test: `src/test/java/com/prompthub/settlement/domain/model/SettlementSourceLineTest.java`

**Interfaces:**
- Consumes: 기존 `SettlementSourceLine.paid(...)`, `markSettled(UUID)`, `getSettlementId()`, `isSettled()`.
- Produces: `SettlementSourceLine.release(UUID settlementId)` → void. 현재 `settlementId`가 인자와 일치할 때만 `settlementId = null`로 복귀. 불일치/이미 null이면 변화 없음(멱등).

- [ ] **Step 1: 실패하는 테스트 작성**

먼저 `SettlementSourceLineTest.java`를 열어 기존 픽스처 헬퍼(소스 라인 생성 메서드) 이름과 시그니처를 확인한다. 아래 테스트는 정산 도장을 찍은 라인을 만들기 위해 `paid(...)` + `markSettled(...)`를 직접 호출한다. 클래스 본문 끝에 추가:

```java
    @Test
    @DisplayName("release: 일치하는 settlementId면 미정산 상태로 되돌린다")
    void release_matchingSettlementId_clears() {
        UUID settlementId = UUID.randomUUID();
        SettlementSourceLine line = SettlementSourceLine.paid(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), LocalDateTime.of(2026, 6, 15, 10, 0));
        line.markSettled(settlementId);

        line.release(settlementId);

        assertThat(line.isSettled()).isFalse();
        assertThat(line.getSettlementId()).isNull();
    }

    @Test
    @DisplayName("release: 다른 settlementId면 풀지 않는다")
    void release_differentSettlementId_keeps() {
        UUID settlementId = UUID.randomUUID();
        SettlementSourceLine line = SettlementSourceLine.paid(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), LocalDateTime.of(2026, 6, 15, 10, 0));
        line.markSettled(settlementId);

        line.release(UUID.randomUUID());

        assertThat(line.getSettlementId()).isEqualTo(settlementId);
    }

    @Test
    @DisplayName("release: 미정산 라인은 그대로 둔다")
    void release_unsettled_noop() {
        SettlementSourceLine line = SettlementSourceLine.paid(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), LocalDateTime.of(2026, 6, 15, 10, 0));

        line.release(UUID.randomUUID());

        assertThat(line.isSettled()).isFalse();
    }
```

import가 없으면 추가: `import java.math.BigDecimal;`, `import java.time.LocalDateTime;`, `import java.util.UUID;`, `import static org.assertj.core.api.Assertions.assertThat;`.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.domain.model.SettlementSourceLineTest"`
Expected: 컴파일 에러(`release` 미존재).

- [ ] **Step 3: `release()` 구현**

`SettlementSourceLine.java`의 `markSettled(...)` 메서드 바로 아래에 추가:

```java
	public void release(UUID settlementId) {
		if (this.settlementId != null && this.settlementId.equals(settlementId)) {
			this.settlementId = null;
		}
	}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.domain.model.SettlementSourceLineTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/prompthub/settlement/domain/model/SettlementSourceLine.java \
        src/test/java/com/prompthub/settlement/domain/model/SettlementSourceLineTest.java
git commit -m "feat: 정산 소스 라인 release 메서드 추가"
```

---

### Task 3: 아웃바운드 포트/어댑터 보강 + 애플리케이션 `cancel()`

**Files:**
- Modify: `src/main/java/com/prompthub/settlement/domain/repository/SettlementRepository.java`
- Modify: `src/main/java/com/prompthub/settlement/domain/repository/SettlementSourceRepository.java`
- Modify: `src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementRepositoryAdapter.java`
- Modify: `src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementSourceRepositoryAdapter.java`
- Modify: `src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementSourceLineJpaRepository.java`
- Modify: `src/main/java/com/prompthub/settlement/application/usecase/SettlementUseCase.java`
- Modify: `src/main/java/com/prompthub/settlement/application/service/SettlementApplicationService.java`
- Test: `src/test/java/com/prompthub/settlement/application/service/SettlementApplicationServiceTest.java`

**Interfaces:**
- Consumes:
  - `Settlement.cancel(LocalDateTime)` (Task 1)
  - `SettlementSourceLine.release(UUID)` (Task 2)
  - 기존 `SettlementException(ErrorCode)` 생성자, `SettlementErrorCode.SETTLEMENT_NOT_FOUND` (Task 4에서 추가 — 본 task는 Task 4와 함께 빌드되어야 컴파일된다. 실행 순서상 Task 4를 먼저 하거나, 본 task에서 enum 상수만 우선 추가한다. 아래 Step 0 참고)
- Produces:
  - `SettlementRepository.findById(UUID)` → `Optional<Settlement>`
  - `SettlementSourceRepository.findBySettlementId(UUID)` → `List<SettlementSourceLine>`
  - `SettlementUseCase.cancel(UUID settlementId)` → `Settlement` (취소된 도메인 엔티티 반환; 컨트롤러가 Response로 변환)
  - `SettlementApplicationService.cancel(...)` 구현 (`@Transactional`)

- [ ] **Step 0: `SETTLEMENT_NOT_FOUND` 상수 선확보**

`SettlementErrorCode.java`의 enum 목록에 다음 한 줄을 먼저 추가한다(전체 에러코드/핸들러는 Task 4에서 마무리). 마지막 상수의 `;`를 `,`로 바꾸고 추가:

```java
	SETTLEMENT_SOURCE_LINE_ALREADY_SETTLED("S-009", "이미 정산에 포함된 소스 라인입니다.", HttpStatus.CONFLICT),
	SETTLEMENT_NOT_FOUND("S-010", "정산을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
```

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

`SettlementApplicationServiceTest.java`는 현재 `SettlementQueryRepository` 하나만 `@Mock`으로 갖는다. cancel은 `SettlementRepository`·`SettlementSourceRepository`를 쓰므로 mock 두 개를 추가한다. 클래스 상단 필드에 추가:

```java
    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementSourceRepository settlementSourceRepository;
```

import 추가:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.Optional;
```

클래스 본문 끝에 테스트 추가. 소스 라인은 `paid(...)` + `markSettled(settlementId)`로 만들어, 취소 시 풀리는지 확인한다:

```java
    @Test
    @DisplayName("취소: 정산을 CANCELLED로 바꾸고 묶인 소스 라인을 모두 푼다")
    void cancel_cancelsSettlementAndReleasesLines() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        org.springframework.test.util.ReflectionTestUtils.setField(target, "id", settlementId);

        SettlementSourceLine line1 = settledLine(settlementId);
        SettlementSourceLine line2 = settledLine(settlementId);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));
        given(settlementSourceRepository.findBySettlementId(settlementId))
                .willReturn(List.of(line1, line2));

        Settlement result = settlementApplicationService.cancel(settlementId);

        assertThat(result.getSettlementStatus()).isEqualTo(SettlementStatus.CANCELLED);
        assertThat(result.getCanceledAt()).isNotNull();
        assertThat(line1.isSettled()).isFalse();
        assertThat(line2.isSettled()).isFalse();
    }

    @Test
    @DisplayName("취소: 정산이 없으면 SettlementException(SETTLEMENT_NOT_FOUND)을 던지고 소스 라인을 조회하지 않는다")
    void cancel_notFound_throws() {
        UUID settlementId = UUID.randomUUID();
        given(settlementRepository.findById(settlementId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementApplicationService.cancel(settlementId))
                .isInstanceOf(SettlementException.class);

        then(settlementSourceRepository).should(never()).findBySettlementId(settlementId);
    }

    private SettlementSourceLine settledLine(UUID settlementId) {
        SettlementSourceLine line = SettlementSourceLine.paid(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), LocalDateTime.of(2026, 6, 15, 10, 0));
        line.markSettled(settlementId);
        return line;
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.application.service.SettlementApplicationServiceTest"`
Expected: 컴파일 에러(`cancel`, 포트 메서드 미존재).

- [ ] **Step 3: 포트에 조회 메서드 추가**

`SettlementRepository.java` — import `java.util.Optional;` 추가하고 메서드 추가:

```java
    Optional<Settlement> findById(UUID id);
```

`SettlementSourceRepository.java` — 메서드 추가:

```java
    List<SettlementSourceLine> findBySettlementId(UUID settlementId);
```

- [ ] **Step 4: 어댑터·JpaRepository 위임 구현**

`SettlementRepositoryAdapter.java` — import `java.util.Optional;` 추가하고 메서드 추가:

```java
    @Override
    public Optional<Settlement> findById(UUID id) {
        return jpaRepository.findById(id);
    }
```

(`SettlementJpaRepository`는 `JpaRepository<Settlement, UUID>`라 `findById`를 이미 제공하므로 별도 선언 불필요.)

`SettlementSourceLineJpaRepository.java` — 파생 쿼리 메서드 추가:

```java
    List<SettlementSourceLine> findBySettlementId(UUID settlementId);
```

`SettlementSourceRepositoryAdapter.java` — 메서드 추가:

```java
    @Override
    public List<SettlementSourceLine> findBySettlementId(UUID settlementId) {
        return jpaRepository.findBySettlementId(settlementId);
    }
```

- [ ] **Step 5: 인바운드 포트에 `cancel` 추가**

`SettlementUseCase.java` — import `com.prompthub.settlement.domain.model.Settlement;`, `java.util.UUID;` 추가하고 메서드 추가:

```java
    Settlement cancel(UUID settlementId);
```

- [ ] **Step 6: 서비스에 `cancel` 구현**

`SettlementApplicationService.java`는 클래스에 `@Transactional(readOnly = true)`가 걸려 있다. cancel은 쓰기이므로 **메서드에 `@Transactional`을 별도로 붙여 오버라이드**한다.

필드 주입 추가(생성자는 `@RequiredArgsConstructor`라 final 필드만 추가하면 됨):

```java
    private final SettlementRepository settlementRepository;
    private final SettlementSourceRepository settlementSourceRepository;
```

import 추가:

```java
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.UUID;
```

메서드 추가:

```java
    @Override
    @Transactional
    public Settlement cancel(UUID settlementId) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new SettlementException(SettlementErrorCode.SETTLEMENT_NOT_FOUND));
        settlement.cancel(LocalDateTime.now());
        List<SettlementSourceLine> lines = settlementSourceRepository.findBySettlementId(settlementId);
        lines.forEach(line -> line.release(settlementId));
        return settlement;
    }
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "com.prompthub.settlement.application.service.SettlementApplicationServiceTest"`
Expected: PASS (기존 + 신규 2개).

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/prompthub/settlement/domain/repository/SettlementRepository.java \
        src/main/java/com/prompthub/settlement/domain/repository/SettlementSourceRepository.java \
        src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementRepositoryAdapter.java \
        src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementSourceRepositoryAdapter.java \
        src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementSourceLineJpaRepository.java \
        src/main/java/com/prompthub/settlement/application/usecase/SettlementUseCase.java \
        src/main/java/com/prompthub/settlement/application/service/SettlementApplicationService.java \
        src/main/java/com/prompthub/settlement/global/exception/SettlementErrorCode.java \
        src/test/java/com/prompthub/settlement/application/service/SettlementApplicationServiceTest.java
git commit -m "feat: 정산 취소 유스케이스와 소스 라인 복귀 구현"
```

---

### Task 4: 예외 매핑 — `SettlementErrorCode` + `GlobalExceptionHandler`

**Files:**
- Modify: `src/main/java/com/prompthub/settlement/global/exception/SettlementErrorCode.java`
- Modify: `src/main/java/com/prompthub/settlement/global/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: `SettlementAlreadyPaidException`, `SettlementAlreadyCancelledException` (Task 1), 기존 `ErrorResponse.of(ErrorCode)`.
- Produces: 도메인 예외 2종 → 409 매핑 핸들러, `SETTLEMENT_ALREADY_PAID`·`SETTLEMENT_ALREADY_CANCELLED` 에러 코드.

> 이 task는 코드 자체는 단순하나, 매핑이 enum/핸들러 양쪽에 일관되게 들어갔는지가 리뷰 포인트라 독립 task로 둔다. 단위 테스트 대신 핸들러 표준 패턴 준수와 전체 빌드로 검증한다.

- [ ] **Step 1: 에러 코드 2종 추가**

`SettlementErrorCode.java` — Task 3 Step 0에서 추가한 `SETTLEMENT_NOT_FOUND` 줄의 `;`를 `,`로 바꾸고 두 줄 추가:

```java
	SETTLEMENT_NOT_FOUND("S-010", "정산을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	SETTLEMENT_ALREADY_PAID("S-011", "이미 지급 완료된 정산은 취소할 수 없습니다.", HttpStatus.CONFLICT),
	SETTLEMENT_ALREADY_CANCELLED("S-012", "이미 취소된 정산입니다.", HttpStatus.CONFLICT);
```

- [ ] **Step 2: 핸들러 매핑 2종 추가**

`GlobalExceptionHandler.java` — import 추가:

```java
import com.prompthub.settlement.domain.exception.SettlementAlreadyCancelledException;
import com.prompthub.settlement.domain.exception.SettlementAlreadyPaidException;
```

기존 `handleSettlementSourceLineAlreadySettled` 핸들러 아래에 추가:

```java
    @ExceptionHandler(SettlementAlreadyPaidException.class)
    public ResponseEntity<ErrorResponse> handleSettlementAlreadyPaid(
            SettlementAlreadyPaidException exception) {
        log.warn("정산 취소 불가(이미 지급 완료) - {}", exception.getMessage());
        ErrorCode errorCode = SettlementErrorCode.SETTLEMENT_ALREADY_PAID;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(SettlementAlreadyCancelledException.class)
    public ResponseEntity<ErrorResponse> handleSettlementAlreadyCancelled(
            SettlementAlreadyCancelledException exception) {
        log.warn("정산 취소 불가(이미 취소됨) - {}", exception.getMessage());
        ErrorCode errorCode = SettlementErrorCode.SETTLEMENT_ALREADY_CANCELLED;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }
```

- [ ] **Step 3: 전체 빌드 확인**

Run: `./gradlew test`
Expected: 전체 PASS (컴파일 + 기존 테스트 모두 통과).

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/prompthub/settlement/global/exception/SettlementErrorCode.java \
        src/main/java/com/prompthub/settlement/global/exception/GlobalExceptionHandler.java
git commit -m "feat: 정산 취소 도메인 예외를 ErrorCode로 매핑"
```

---

### Task 5: 표현 — `SettlementResponse` + 취소 엔드포인트

**Files:**
- Create: `src/main/java/com/prompthub/settlement/presentation/dto/response/SettlementResponse.java`
- Modify: `src/main/java/com/prompthub/settlement/presentation/controller/SettlementController.java`

**Interfaces:**
- Consumes: `SettlementUseCase.cancel(UUID)` → `Settlement` (Task 3), `Settlement.getId/getSellerId/displayStatus/getCanceledAt`, `ApiResult.success(T)`.
- Produces:
  - `SettlementResponse(UUID settlementId, UUID sellerId, String displayStatus, LocalDateTime canceledAt)` + `from(Settlement)`
  - `POST {api.init}/admin/settlements/{settlementId}/cancel` → `ApiResult<SettlementResponse>` (200)

- [ ] **Step 1: 단건 응답 DTO 작성**

`SettlementResponse.java`:

```java
package com.prompthub.settlement.presentation.dto.response;

import com.prompthub.settlement.domain.model.Settlement;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "정산 단건 응답")
public record SettlementResponse(
        @Schema(description = "정산 ID(UUID)")
        UUID settlementId,

        @Schema(description = "판매자 ID(UUID)")
        UUID sellerId,

        @Schema(description = "정산 표시 상태", example = "CANCELLED")
        String displayStatus,

        @Schema(description = "취소 시각(취소된 경우)", example = "2026-06-24T09:00:00")
        LocalDateTime canceledAt
) {

    public static SettlementResponse from(Settlement settlement) {
        return new SettlementResponse(
                settlement.getId(),
                settlement.getSellerId(),
                settlement.displayStatus().name(),
                settlement.getCanceledAt());
    }
}
```

- [ ] **Step 2: 컨트롤러에 취소 엔드포인트 추가**

`SettlementController.java` — import 추가:

```java
import com.prompthub.settlement.presentation.dto.response.SettlementResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
```

`getList` 메서드 아래에 핸들러 추가:

```java
    @PostMapping("/{settlementId}/cancel")
    @Operation(summary = "정산 취소",
            description = "지급 완료(PAID) 전 정산을 취소하고, 묶인 소스 라인을 풀어 재정산 대상으로 되돌립니다. ADMIN 권한이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 성공",
                    content = @Content(schema = @Schema(implementation = SettlementResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "정산 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "이미 지급 완료됨 / 이미 취소됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementResponse> cancel(
            @Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId) {
        return ApiResult.success(SettlementResponse.from(settlementUseCase.cancel(settlementId)));
    }
```

- [ ] **Step 3: 전체 빌드·테스트 확인**

Run: `./gradlew test`
Expected: 전체 PASS.

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/prompthub/settlement/presentation/dto/response/SettlementResponse.java \
        src/main/java/com/prompthub/settlement/presentation/controller/SettlementController.java
git commit -m "feat: 정산 취소 API 엔드포인트 추가"
```

---

## 검증 (전체)

- [ ] `./gradlew test` 전체 통과
- [ ] (선택) 앱 실행 후 `POST /admin/settlements/{id}/cancel` 호출 → 200 + `displayStatus=CANCELLED`, 같은 기간 수동 정산 재실행 시 새 정산 생성 확인
- [ ] `verify-rules` 스킬로 룰 7종 검증 후 PR

## 비범위 (YAGNI)

- 취소 주체·사유 기록, 즉시 자동 재계산, PAID 건 취소(지급금 회수), 별도 취소 이력 테이블.

## 참고: 컨벤션 메모

- 명령 흐름이지만 `~Result`를 따로 두지 않고, 서비스가 **도메인 `Settlement`를 반환**하고 컨트롤러가 `SettlementResponse.from()`으로 변환한다. application이 presentation을 import하지 않으므로(서비스는 도메인만 다룸) 의존 방향(§1)에 부합하며, 도메인 모델은 컨트롤러에서 즉시 Response로 변환되어 응답으로 직접 노출되지 않는다. (clean-architecture §7)
- `ReflectionTestUtils`로 상태를 세팅하는 것은 **테스트 코드 한정**이다. 운영 코드에는 setter를 추가하지 않는다.
