---
name: test-first-unit-test
description: >-
  정산·주문·결제처럼 데이터 오류 영향이 큰 기능, 또는 계산 로직·상태 전이·중복 검증·권한 검증·예외
  정책이 있는 기능을 구현하기 직전에 사용한다. "기능 구현해줘", "이 로직 만들어줘", "서비스 메서드
  추가", "API 추가"처럼 구현을 요청받았을 때, 구현 코드를 짜기 전에 반드시 이 스킬을 먼저 써서 도메인·
  애플리케이션 단위 테스트를 먼저 작성한다. 도메인 규칙을 코드로 먼저 고정해야 할 때.
---

# Test First Unit Test (settlement-service)

## 개요

정산 서비스에서 **기능을 구현하기 전에 핵심 비즈니스 규칙을 단위 테스트로 먼저 정의**한다.
테스트는 구현 세부사항이 아니라 *무엇이 일어나야 하는지(should do)*를 검증한다 — 도메인 규칙,
계산 결과, 상태 전이, 예외 조건, 유스케이스 흐름, 저장 여부, 외부 의존성 호출 여부.

**핵심 원칙:** 테스트가 실패하는 걸 직접 보지 않았다면, 그 테스트가 올바른 것을 검증하는지 알 수 없다.
구현 후에 쓴 테스트는 곧바로 통과해버려서 *무엇도 증명하지 못한다.*

**REQUIRED BACKGROUND:** TDD의 RED-GREEN-REFACTOR 사이클 자체는 `superpowers:test-driven-development`
가 정의한다. 이 스킬은 그 사이클을 **정산 서비스의 계층 구조·테스트 스택에 특화**한 가이드다. TDD의
일반 규율(실패를 먼저 본다, 최소 구현, mock 남용 금지)은 그 스킬을 따른다.

## 이 프로젝트의 철칙

```
구현 전에 실패하는 단위 테스트부터. 도메인 규칙이 있는 기능은 예외 없이.
```

규칙의 글자를 어기는 건 규칙의 정신을 어기는 것이다. "이번 한 번만 구현부터"는 합리화다.

## 언제 "구현 전" 테스트를 먼저 쓰는가

아래 중 **하나라도** 해당하면 구현 전에 단위 테스트를 먼저 쓴다.

- 계산 로직이 있다. (합계·수수료·실지급액 등)
- 상태 변경/전이 규칙이 있다.
- 중복 검증이 있다.
- 소유자/권한 검증이 있다.
- 예외 발생 조건이 있다.
- 돈·주문·결제·정산처럼 데이터 오류 영향이 크다.
- Spring·DB·외부 API 없이 순수 Java(또는 Mock)로 검증할 수 있다.

판단 한 문장:

> 이 기능의 규칙을 Spring·DB·외부 API 없이 검증할 수 있다면 → 구현 전에 단위 테스트를 먼저 쓴다.
> 실제 DB·HTTP·Security·Batch·Transaction 동작을 봐야 의미가 있다면 → 구현 후 통합 테스트로 미룬다.

## 계층별 우선순위와 시점

이 프로젝트는 헥사고날 구조(`clean-architecture.md`)다. 계층에 따라 테스트 종류와 시점이 다르다.

| 계층 | 테스트 종류 | 시점 | Mock |
| --- | --- | --- | --- |
| domain/model | 도메인 단위 테스트 | **구현 전** | 쓰지 않음 (순수 객체) |
| application/service | 애플리케이션 서비스 단위 테스트 | **구현 전** | 외부 의존성만 |
| infrastructure/persistence | Repository 통합 테스트 | 구현 후 | — |
| presentation/controller | Controller(JSON) 테스트 | 구현 후 | — |
| infrastructure/batch | Batch Job 테스트 | 구현 후 | — |

구현 전 우선순위: **1) Domain → 2) Application Service.** 나머지(Repository 쿼리/Fetch Join·N+1,
Controller 응답, Security 인가, Batch Job, 외부 API 연동, Transaction)는 실제 인프라 동작을 봐야
의미가 있으므로 구현 후에 작성한다.

## 작성 순서 — 구현 전에 실패를 본다

핵심은 *케이스를 몇 개씩 쓰느냐*가 아니라 **구현 코드를 쓰기 전에 테스트가 실패하는 걸 직접 보는
것**이다. 테스트와 구현을 함께 써서 RED를 한 번도 못 보는 상황만 피하면 된다.

- 같은 메서드의 같은 불변식이면 케이스 여러 개(성공/실패/경계)를 한 번에 써서 함께 RED→GREEN 해도 된다.
- 서로 다른 규칙·메서드라면 규칙 단위로 나눠 RED를 본다. 한 번에 다 짜놓고 구현부터 들어가지 않는다.

1. 대상 기능의 비즈니스 규칙을 정리한다. (성공/실패/경계 케이스)
2. 한 규칙(또는 그 규칙의 케이스들)에 대한 도메인 단위 테스트를 쓴다.
3. 돌려서 **실패를 확인한다.** (컴파일 에러가 아니라, 기능이 없어서 실패하는지)
4. 통과시킬 **최소 구현**을 한다.
5. 다시 돌려서 통과 + 다른 테스트도 깨지지 않음을 확인한다.
6. 다음 규칙으로 2~5 반복. 도메인을 다 덮으면 애플리케이션 서비스로 같은 사이클.
7. Repository/Controller/Batch/통합 테스트는 구현 후에 추가한다.

## 테스트 스택과 실행

- JUnit5(`org.junit.jupiter`) + AssertJ(`assertThat`) + Mockito(서비스 테스트의 외부 의존성).
- 구조는 Given/When/Then, 메서드명·`@DisplayName`으로 정책이 드러나게.
- 실행: 작성한 테스트만 빠르게 돌린다.

```bash
./gradlew test --tests "com.prompthub.settlement.domain.model.SettlementBatchTest"
```

## Mock 규칙

도메인 단위 테스트는 **Mock을 쓰지 않는다.** 엔티티·값 객체·계산 로직을 mock하면 코드가 아니라
mock을 테스트하게 된다.

```java
// 나쁜 예 — 도메인을 mock
@Mock SettlementBatch batch;

// 좋은 예 — 진짜 객체
SettlementBatch batch = SettlementBatch.start("B-001", start, end, TriggerType.SCHEDULED);
```

애플리케이션 서비스 테스트는 **외부 의존성만** mock한다. (Repository, 외부 API Client, 메시지
Producer, 파일 스토리지, Payment Gateway 등) 엔티티·값 객체·계산기·Validator는 mock하지 않는다.

## 예시 1 — 상태 전이 (도메인 단위 테스트)

`SettlementBatch.complete()`는 PROCESSING일 때만 완료된다. 아니면 도메인 순수 예외를 던진다.

```java
@Test
@DisplayName("PROCESSING 상태가 아닌 배치는 완료할 수 없다")
void complete_notProcessing_throwsException() {
    // given
    SettlementBatch batch = SettlementBatch.start("B-001",
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), TriggerType.SCHEDULED);
    batch.complete(); // PROCESSING → COMPLETED

    // when & then — 이미 완료된 배치를 다시 완료
    assertThatThrownBy(batch::complete)
            .isInstanceOf(SettlementBatchInvalidStateException.class);
}
```

## 예시 2 — 계산 로직 (도메인 단위 테스트)

`Settlement.create()`는 상세 목록으로부터 건수·총액·수수료·실지급액을 **파생 계산**한다.
외부에서 합계를 받지 않으므로, 상세와 합계가 어긋나지 않는지 검증한다.

```java
@Test
@DisplayName("정산 생성 시 상세 합계로 총액·수수료·실지급액을 계산한다")
void create_calculatesTotalsFromDetails() {
    // given
    List<SettlementDetail> details = List.of(detail("100.00", "10.00"), detail("200.00", "20.00"));

    // when
    Settlement settlement = Settlement.create(batchId, sellerId, YearMonth.of(2026, 6), details);

    // then
    assertThat(settlement.getProductCount()).isEqualTo(2);
    assertThat(settlement.getTotalAmount()).isEqualByComparingTo("300.00");
    assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo("30.00");
    assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING_APPROVAL);
}
```

> `BigDecimal`은 `equals`가 scale까지 비교하므로 `isEqualByComparingTo`로 값만 비교한다.

## 테스트 케이스 체크리스트

기능마다 최소한 검토한다.

- [ ] 정상 입력이면 성공하는가?
- [ ] 잘못된 입력/규칙 위반이면 예외가 발생하는가? (도메인은 순수 예외)
- [ ] 중복 요청을 막는가?
- [ ] 권한 없는 사용자의 접근을 막는가?
- [ ] 실패 시 저장이 발생하지 않는가? / 성공 시 필요한 저장이 발생하는가?
- [ ] 외부 의존성 호출 여부가 적절한가? (`verify`)
- [ ] 반환 Result/DTO 값이 기대와 일치하는가?
- [ ] 테스트 이름만 봐도 정책이 이해되는가?

## 출력 형식

구현 요청을 받으면 다음 순서로 답한다.

1. 이 기능에서 먼저 테스트할 비즈니스 규칙
2. 구현 전 작성할 단위 테스트 목록 (도메인 → 서비스)
3. 구현 후로 미룰 통합 테스트 목록
4. 첫 테스트 코드 (RED) — 한 개
5. 실행 명령과 "실패 확인" 안내

## 합리화 차단표

압박(시간 없음, 이미 구현함)이 오면 아래 핑계가 떠오른다. 전부 "구현부터" 하라는 함정이다.

| 핑계 | 실제 |
| --- | --- |
| "너무 단순해서 테스트 불필요" | 단순한 코드도 깨진다. 테스트 30초면 쓴다. |
| "구현부터 하고 테스트는 나중에" | 나중 테스트는 곧바로 통과해 아무것도 증명 못 한다. |
| "이미 수동으로 확인했다" | 임시 확인은 기록도 없고 재실행도 안 된다. |
| "정산은 복잡해서 먼저 짜봐야 안다" | 테스트하기 어렵다 = 설계가 안 잡혔다는 신호. 테스트가 설계를 끌어낸다. |
| "이번 기능은 예외" | 돈·정산일수록 예외가 아니라 1순위 대상이다. |
| "통합 테스트로 한 번에 보겠다" | 통합은 느리고 원인을 못 짚는다. 규칙은 단위로 먼저. |

## Red Flags — 멈추고 다시

- 테스트보다 구현 코드를 먼저 쓰고 있다.
- 테스트가 처음부터 통과한다. (실패를 본 적이 없다)
- 왜 실패했는지 설명하지 못한다.
- 도메인 객체를 mock하고 있다.
- "이건 달라서…", "정신은 지켰으니까…"라고 말하고 있다.

위 중 하나라도 해당하면: 구현을 멈추고, 실패하는 단위 테스트부터 다시 시작한다.
