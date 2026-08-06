# 게이트웨이 라우트·화이트리스트 API 스펙 동기화

여러 서비스의 API 스펙이 바뀌면서 `VersionedServiceRoute`, `WhitelistPathResolver`를 새 스펙에 맞춰 동기화한다.

## 결정

1. **notification-service 신규 편입.** `lb://NOTIFICATION-SERVICE`, pathSuffixes `["/notifications", "/notifications/**"]`, order=5 (2026-07-22 정정 — 작성 시점엔 6으로 기재했으나 settlement-service 라우트 제거 이후 실제 코드는 5로 재번호됨). 인증 필요(화이트리스트 없음).
2. **settlement-service를 게이트웨이 라우트에서 완전히 제거.** 정산 관리 API가 전부 admin-service로 흡수되어 settlement-service는 더 이상 외부(게이트웨이 대상) API를 노출하지 않는다. 기존에 `order` 필드를 도입한 이유(설정/정산 배치 경로가 admin의 상위 경로보다 먼저 매칭돼야 함)가 사라지므로 해당 항목만 삭제하고, 남은 서비스 간 경로 접두사는 서로 안 겹쳐 나머지 order 값은 그대로 둔다.
3. **auth를 OAuth 전용으로 전환.** 패스워드 기반 가입/로그인을 폐지하여 `AUTH_PATH_SUFFIXES`에서 `/auth/signup`, `/auth/login`을 제거한다(`/auth/oauth/**`, `/auth/token/refresh`는 유지). `GET /internal/authorize/{userId}`는 게이트웨이 필터가 직접 호출하는 내부 전용 호출이라 라우팅 테이블에 올리지 않는다(ADR-0008 forward-auth 패턴과 일치).
4. **product-service 경로 구조 변경.** `/sellers/me/products` 형태가 `/products/sellers/me`로 통합되어, product-service pathSuffixes를 `["/products", "/products/**"]` 두 개로 단순화한다.
5. **`productReadWhitelist`를 블랙리스트에서 화이트리스트 방식으로 전환.** 기존엔 `GET` 메서드 한정으로 `/products/**`를 통째로 permitAll 처리했는데, `GET /products/sellers/me`, `GET /products/sellers/me/summary`(인증 필요, SELLER 전용)가 같은 `/products` 하위로 들어오면서 이 통짜 와일드카드가 그대로면 실수로 공개돼버리는 문제가 생긴다. 명시적으로 공개 경로만 나열하는 방식(`/products`, `/products/*`, `/products/*/recommends`)으로 바꿔, 앞으로 `/products` 하위에 새 경로가 추가돼도 기본값이 "인증 필요"가 되도록 한다.
6. **user-service에 판매자 조회용 공개 엔드포인트 추가.** `GET /sellers/product`(단건 조회 — 2026-07-22 정정, 작성 시점엔 POST로 기재했으나 실제 코드·`docs/api-spec/user.md`는 GET), `POST /sellers/products`(다건 조회, 요청/응답 파라미터 때문에 POST 사용)는 인증 불필요 — 새 화이트리스트 메서드로 버전별 조합.

## 배경

- `GET /orders/product/{productId}/paid`는 인증 필요 유지: 요청 경로엔 상품 ID만 있고 "누구의" 구매 여부인지는 게이트웨이가 JWT에서 뽑아 주입하는 `X-User-Id`에 의존하므로, 화이트리스트에 넣으면 애초에 판별이 불가능해진다.
- admin-service의 `/admin/sellers/register/{registerId}/approve`, `.../reject`는 기존 `/admin/sellers/register/**` 와일드카드로 이미 커버되어 pathSuffixes 변경이 필요 없다.
