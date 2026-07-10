# 도메인 모델 컨벤션

정산 서비스의 도메인 모델(엔티티) 작성 규칙과 Lombok 사용 범위를 정의한다.

> 관련 문서
> - 패키지 구조·계층 규칙: `clean-architecture.md`
> - 도메인 예외 처리 방식(순수 예외 → 핸들러 매핑): `controller-exception.md` §2-4

엔티티는 단순 데이터 객체가 아니라 **도메인 상태와 규칙을 보장하는 객체**다. 아래 규칙은
그 관점에서의 가이드이며, **참고 기준**으로 본다. 모든 객체에 기계적으로 적용하지 않고,
**잘못 생성되면 도메인 규칙이 깨지는 엔티티(`Settlement`·`SettlementBatch` 등)에 우선 적용한다.**

## 1. 도메인 모델 = 엔티티 겸용 정책

`domain/model` 클래스에 `@Entity`를 직접 붙여 JPA 엔티티로도 사용한다.
별도 엔티티 클래스와 매퍼를 두지 않는다. (실용적 타협 — 매퍼 보일러플레이트 제거)

도메인이 JPA에 의존하게 되는 대신, 다음을 지켜 모델이 단순 데이터 덩어리로 전락하지 않게 한다.

- **비즈니스 상태 변경용 public setter 를 두지 않는다.** 상태 변경은 의도를 드러내는
  도메인 메서드로 표현한다. (예: `order.markPaid()`, `settlementBatch.complete()`, `settlementBatch.fail(...)`)
- 비즈니스 규칙·불변식은 도메인 메서드 안에서 보장한다.
- **공통 생성일·수정일(`createdAt`·`updatedAt`)은 `BaseEntity` 에서 관리하고, 개별 엔티티에서
  중복 선언하지 않는다.** 비즈니스 시각(`calculatedAt`·`executedAt` 등)은 엔티티별 필드로 둔다.
- **반복되는 비즈니스 규칙이 실제로 해당 aggregate 에 속한다면 도메인 모델로 이동한다.**
  서비스에 흩어진 규칙을 도메인이 끌어안는 방향으로 정리한다.
- **Entity 를 단순 데이터 컨테이너로만 사용하지 않는다.** 행위 없는 getter/setter 덩어리는 지양한다.
- 연관관계는 꼭 필요한 경우만. 지연 로딩 기본, 양방향은 최소화한다.

## 2. 엔티티 기본 원칙

엔티티는 다음을 지향한다.

- 외부에서 무분별하게 생성되지 않게 한다.
- 생성 시 필수값과 초기 상태를 보장한다.
- 상태 변경은 의미 있는 도메인 메서드로만 한다.
- public setter·무분별한 `@Builder` 사용을 지양한다.
- DB 제약조건뿐 아니라 엔티티 내부에서도 최소한의 불변식을 검증한다.

## 3. 기본 생성자

모든 JPA 엔티티는 프록시 생성을 위해 `protected` 기본 생성자를 둔다. `public` 기본 생성자는 쓰지 않는다.

```java
@NoArgsConstructor(access = AccessLevel.PROTECTED)
```

## 4. 생성은 정적 팩토리 + private 생성자

도메인 규칙이 있는 엔티티는 `public` 생성자나 `@Builder` 보다 **정적 팩토리 메서드**를 우선한다.
정적 팩토리 이름은 `of`·`from` 보다 가능하면 **도메인 행위**를 드러낸다.

```java
Settlement.create(...)
SettlementBatch.start(...)
Order.place(...)
Payment.confirm(...)
```

생성 시점에 아래 중 하나라도 해당하면 `static factory + private constructor` 를 쓴다.

- 초기 상태가 정해져 있다.
- 필수값 검증이 필요하다.
- 상태 전이가 중요한 엔티티다.
- 내부 합계·파생값을 계산한다.
- 자식 엔티티 목록과 정합성을 유지해야 한다.

```java
public static Settlement create(...) {
    return new Settlement(...);
}

private Settlement(...) {
    // 필수값 검증
    // 필수값 세팅
    // 초기 상태 세팅
    // 파생값 계산
}
```

## 5. 초기 상태·파생값은 엔티티 내부에서 결정한다

**초기 상태는 외부에서 받지 않고 엔티티 내부에서 강제한다.**

```java
// 지양: SettlementBatch.start(..., SettlementBatchStatus.COMPLETED);
// 권장:
this.status = SettlementBatchStatus.PROCESSING;
```

**상세 목록·원본 데이터로부터 계산 가능한 값(파생값)은 외부에서 직접 받지 않는다.**
외부에서 합계를 직접 받으면 상세 내역과 총액이 어긋날 수 있다.

```java
this.productCount = details.size();
this.totalAmount = sum(details, SettlementDetail::getLineAmount);
this.feeTotalAmount = sum(details, SettlementDetail::getFeeAmount);
```

## 6. 시간 값은 가능하면 외부에서 주입한다 (참고)

> 참고용 권장 항목이다. 현재 코드(`Settlement.create()`·`SettlementBatch.complete()`)는 내부에서
> `LocalDateTime.now()` 를 호출하고 있고, 이를 강제로 바꾸지는 않는다. 신규 작성·리팩터링 시 참고한다.

엔티티 내부에서 `LocalDateTime.now()` 를 직접 호출하면 시점이 코드에 박혀 테스트가 어렵다.
가능하면 현재 시각은 Application Service·Batch·`TimeProvider` 에서 만들어 주입한다.

```java
// 참고: this.calculatedAt = LocalDateTime.now();  → this.calculatedAt = calculatedAt;
```

## 7. 컬렉션 필드 규칙

컬렉션 필드는 선언 시점에 초기화하고, 통째로 교체하지 않는다.

```java
private List<SettlementDetail> details = new ArrayList<>();
```

```java
// 지양: this.details = new ArrayList<>(details);
// 권장:
this.details.addAll(details);
```

양방향 연관관계에서는 연관관계 편의 메서드를 둔다.

```java
public void addDetail(SettlementDetail detail) {
    this.details.add(detail);
    detail.assignSettlement(this);
}
```

## 8. 상태 변경 — 도메인 메서드 + 불변식 검증

상태는 setter 로 직접 바꾸지 않고, 의미 있는 도메인 메서드로 바꾼다.
**상태 변경 메서드 내부에서 현재 상태로부터 전이 가능한지 검증한다.**

불변식 위반은 **도메인 순수 예외**(`extends RuntimeException`, `domain/exception`)로 던진다.
도메인은 `ErrorCode`·`HttpStatus` 를 모르므로 `SettlementException` 을 던지지 않는다.
HTTP 상태로의 변환은 핸들러가 맡는다. (`controller-exception.md` §2-4)

```java
// 지양: settlementBatch.setStatus(COMPLETED);
// 권장:
public void complete() {
    if (this.status != SettlementBatchStatus.PROCESSING) {
        throw new SettlementBatchInvalidStateException(this.status);  // 도메인 순수 예외
    }
    this.status = SettlementBatchStatus.COMPLETED;
}
```

## 9. Lombok 규칙

엔티티가 데이터 컨테이너로 전락하지 않도록 Lombok 사용 범위를 제한한다.

**허용**

- `@Getter`
- 필요한 경우 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`

**지양**

- `@Data` — setter·equals·hashCode 등을 무분별하게 생성한다.
- `@Setter` — 의미 없는 상태 변경 통로를 연다.
- 도메인 엔티티에 대한 public all-args constructor — 생성은 정적 팩토리로 한다.
- **엔티티에 `@RequiredArgsConstructor`.** final 필드용 생성자가 열려 §4 의 "정적 팩토리 + private
  생성자(필수값 검증·파생값 계산)" 규율을 우회한다. → 단, 도메인 서비스·값 객체·설정 객체 등
  **엔티티가 아닌 클래스에서는** DI 등 필요 시 사용할 수 있다.
- **상태·생명주기가 중요한 엔티티(`Settlement`·`SettlementBatch`·`Order`·`Payment` 등)에 `@Builder`.**
  필수값·초기 상태·상태 전이 규칙을 우회할 수 있다. → §4 의 정적 팩토리 + private 생성자를 쓴다.

`@Builder` 는 도메인 규칙이 거의 없는 객체에만 쓴다. (§10 참고)

## 10. DTO 와 엔티티의 생성 규칙 분리

엔티티와 DTO 는 생성 규칙을 다르게 적용한다.

| 대상 | 생성 방식 |
| --- | --- |
| Entity | 정적 팩토리 + private 생성자 우선, `@Builder` 지양 |
| Request DTO | `record` 또는 기본 생성자 허용 |
| Response DTO | `record` + static `from()` 권장 |
| Command 객체 | `record` / `@Builder` 허용 |
| Search Condition | `@Builder` 허용 |
| Test Fixture | `@Builder` 또는 Fixture 메서드 허용 |

## 11. 핵심 요약

도메인 엔티티는 다음 구조를 기본으로 한다. 단, 모든 객체에 기계적으로 적용하지 않고
**잘못 생성되면 도메인 규칙이 깨지는 엔티티에 우선 적용한다.**

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EntityName extends BaseEntity {

    public static EntityName create(...) {       // 생성은 정적 팩토리
        return new EntityName(...);
    }

    private EntityName(...) {                     // 필수값 검증 · 초기 상태 · 파생값 계산
        ...
    }

    public void changeState(...) {               // 상태 검증 후 변경, 위반은 도메인 순수 예외
        ...
    }
}
```
