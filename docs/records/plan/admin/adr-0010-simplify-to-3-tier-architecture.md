# ADR-0010: admin-service는 3-tier 아키텍처로 단순화한다

- 상태: accepted
- 날짜: 2026-07-24
- 관련: admin-service 전체 7개 도메인(auth, home, order, product, seller, settlement, user), 파일럿은 settlement

## 컨텍스트

admin-service는 다른 서비스(user-service, payment-service, product-service, settlement-service)와
동일하게 도메인별 클린 아키텍처(포트/어댑터, `UseCase` 인터페이스)로 정의돼 있었다.

그런데 실제로는 도메인마다 `UseCase` 인터페이스(구현체 1개), `Repository` 인터페이스(JPA
`Adapter` 구현체 1개)가 1:1로만 존재해 확인 결과 7개 `UseCase`, 12개 `Repository` 포트가
전부 구현체 하나짜리였다 — 다형성(구현체 교체) 이득 없이 보일러플레이트만 발생하는 상태.
settlement 도메인은 이와 별개로 `SellerNameQueryPort`라는 cross-domain 포트도 갖고 있었다.

한편 `Product`, `Settlement` 같은 엔티티는 이미 자체 상태 전이 가드(`approve()`, `cancel()`
등)를 메서드로 갖고 있고, `ProductFamily`처럼 DB에 매핑되지 않는 순수 도메인 객체도 이미
존재해 — 로직 배치 자체는 어느 정도 잘 되어 있었고, 문제는 순수하게 포트/어댑터 계층의
보일러플레이트였다.

## 결정

1. **`UseCase` 인터페이스, `Repository` 인터페이스(포트)를 전부 제거한다.** `Controller` →
   `Service`(구현체) → JPA Repository를 직접 참조하는 3-tier로 전환한다. Spring Data
   `JpaRepository`도 인터페이스이므로 Mockito로 그대로 mock 가능해, 이 변경으로 인한 테스트
   영향은 최소다.
2. **도메인 간 참조는 포트/어댑터 없이, 대상 도메인의 `Service` 구현체를 직접 주입해서
   호출한다.** 다른 도메인의 `Repository`를 직접 참조하지 않는다 — 그 도메인의 불변식을
   우회하지 않기 위함이다. 현재 도메인 의존 그래프는 단방향(DAG)이다
   (`product→order`, `seller→auth,user`, `settlement→user`, `user→auth`)이므로 순환참조
   위험은 없다. 향후 양방향 의존이 필요해지면, 이는 "인터페이스가 없어서 생긴 문제"가 아니라
   도메인 경계를 다시 나눠야 한다는 신호로 본다.
3. **패키지 구조를 `domain/application/infrastructure/presentation` →
   `entity/model/service/repository/controller/dto/exception`으로 개편한다.** 도메인을
   최상위 폴더로 유지하고 레이어는 그 아래 서브폴더로 둔다(package by feature). 다른 4개
   서비스가 전부 `{service}.{domain}.{layer}` 순서(도메인 우선)를 쓰고 있어, 여기서 레이어를
   최상위로 올리면(`controller/settlement/...`) admin만 두 번째로 갈라지는 지점이 생긴다.
4. **DB에 매핑되지 않는 순수 도메인 객체(`ProductFamily`류)를 위해 `model/` 폴더를
   `entity/`와 분리해 남긴다.** 실제로 그런 객체가 있는 도메인에만 생성한다(현재는 product만).
5. **Fat Service 방지 기준**: 엔티티 자신의 상태만으로 판단되는 규칙은 엔티티 메서드로,
   여러 엔티티에 걸친 규칙은 `model/` 도메인 객체로, 상태 없이 입력값만으로 계산되는 로직은
   `util`/계산기 클래스로 분리한다. `Service`는 이 셋을 호출하는 오케스트레이션만 담당하고
   조건 분기·계산 로직을 직접 갖지 않는다.
6. **settlement 도메인을 파일럿으로 먼저 적용한다.** 포트(`SellerNameQueryPort`), 상태 전이
   엔티티(`Settlement`), cross-domain 호출까지 이번에 정한 규칙을 전부 검증할 수 있는
   도메인이라 선택했다. 문제 없으면 나머지 6개 도메인으로 순서대로 확산한다.
7. **파일럿 내부 커밋도 4단계로 분리한다**: ① `UseCase` 인터페이스 제거 ② `Repository` 포트
   제거 + `SellerNameQueryPort`를 `UserService` 직접 호출로 교체 ③ 패키지 리네임(순수 이동,
   로직 변경 없음) ④ Fat Service 방지용 로직 추출(해당하는 경우만). 리네임과 동작 변경을
   같은 커밋에 섞지 않는다.

## 결과

- 다른 4개 서비스(user/payment/product/settlement-service)는 여전히 `usecase`(및
  settlement-service는 `port`까지) 패턴을 유지한다 — admin-service만 아키텍처가 갈라지는
  것을 의도적으로 받아들인다. admin-service는 다른 서비스에 비해 구현체를 교체할 필요가
  없는 CRUD성 오케스트레이션 위주라는 실태를 반영한 결정이다.
- 포트/어댑터가 주는 유연성(구현체 교체, 계약 분리)은 포기한다. 실제로 두 번째 구현체가
  필요해지는 시점이 오면 그때 다시 인터페이스를 도입한다.
- `SellerNameQueryAdapter`의 번역 로직(sellerId 리스트 → 이름 맵 변환)은 단순 삭제가 아니라
  `UserService`로 이관해야 한다 — 파일럿에서 실제로 손이 가는 지점이다.
