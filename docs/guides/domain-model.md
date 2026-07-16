# 도메인 모델 컨벤션

사용자 서비스의 도메인 모델(엔티티) 작성 규칙과 Lombok 사용 범위를 정의한다.

> 관련 문서: 패키지 구조·계층 규칙은 `clean-architecture.md` 참고.

## 1. 도메인 모델 = 엔티티 겸용 정책

`domain/model` 클래스에 `@Entity`를 직접 붙여 JPA 엔티티로도 사용한다.
별도 엔티티 클래스와 매퍼를 두지 않는다. (실용적 타협 — 매퍼 보일러플레이트 제거)

도메인이 JPA에 의존하게 되는 대신, 다음을 지켜 모델이 단순 데이터 덩어리로 전락하지 않게 한다.

- **비즈니스 상태 변경용 public setter 를 두지 않는다.** 상태 변경은 의도를 드러내는
  도메인 메서드로 표현한다. (예: `user.block()`, `seller.approve()`)
- 비즈니스 규칙·불변식은 도메인 메서드 안에서 보장한다.
- **반복되는 비즈니스 규칙이 실제로 해당 aggregate 에 속한다면 도메인 모델로 이동한다.**
  서비스에 흩어진 규칙을 도메인이 끌어안는 방향으로 정리한다.
- **Entity 를 단순 데이터 컨테이너로만 사용하지 않는다.** 행위 없는 getter/setter 덩어리는 지양한다.
- 기본 생성자는 JPA용으로 `protected`, 객체 생성은 정적 팩토리 또는 `@Builder`로.
- 연관관계는 꼭 필요한 경우만. 지연 로딩 기본, 양방향은 최소화한다.

```java
// domain/model/User.java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id @GeneratedValue
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    public static User create(...) { ... }   // 생성은 팩토리 / @Builder 로

    public void block() {                    // 상태 변경은 도메인 메서드로
        if (this.status != UserStatus.ACTIVE) {
            throw new UserAlreadyBlockedException(this.userId);
        }
        this.status = UserStatus.BLOCKED;
    }

    public void withdraw() {
        this.status = UserStatus.WITHDRAWN;
    }
}
```

```java
// domain/model/Seller.java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller {

    @Id @GeneratedValue
    private UUID sellerId;

    @Enumerated(EnumType.STRING)
    private SellerStatus status;   // PENDING / ACTIVE / SUSPENDED

    public void approve() {
        if (this.status != SellerStatus.PENDING) {
            throw new SellerAlreadyAppliedException(this.sellerId);
        }
        this.status = SellerStatus.ACTIVE;
        this.approvedAt = LocalDateTime.now();
    }
}
```

## 2. Lombok 규칙

엔티티가 데이터 컨테이너로 전락하지 않도록 Lombok 사용 범위를 제한한다.

**허용**

- `@Getter`
- `@Builder`
- `@RequiredArgsConstructor`
- 필요한 경우 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`

**지양**

- `@Data` — setter·equals·hashCode 등을 무분별하게 생성한다.
- `@Setter` — 의미 없는 상태 변경 통로를 연다.
- 도메인 Entity 에 대한 public all-args constructor — 생성은 정적 팩토리 또는 `@Builder`로 한다.
