# Product Service Testing Rules

## 목적

Product Service 변경 시 어떤 테스트를 추가하거나 수정해야 하는지 정한다.
테스트는 CI 통과만을 위한 파일이 아니라, 프론트와 API 계약, 서비스 로직, DB 조회 조건이 깨지지 않도록 고정하는 기준이다.

## 기본 원칙

- 기능 구현 PR에는 변경 계층에 맞는 테스트를 함께 포함한다.
- 단순 문서 변경은 테스트 파일 추가 없이 product-service build 결과만 확인해도 된다.
- 테스트를 생략해야 한다면 PR 본문에 생략 사유와 남은 위험을 적는다.
- public API는 로그인 없이 동작해야 하므로 `Authorization`, `X-User-Id`, `X-User-Role` 없이 성공 케이스를 검증한다.
- 인증이 필요한 API는 Gateway가 주입하는 `X-User-Id`, `X-User-Role` 헤더 기준으로 테스트한다.
- Controller 테스트에서는 공통 응답 포맷과 HTTP status를 반드시 확인한다.
- Service 테스트에서는 repository/client 호출 조건, 예외, DTO 변환 기준을 확인한다.
- Repository 테스트는 custom query, Querydsl, 복잡한 검색/정렬/필터가 있을 때 추가한다.

## 변경 유형별 테스트 기준

### Controller 변경

`ProductControllerTest`를 추가하거나 수정한다.

확인 항목:

- 요청 경로와 HTTP method
- query parameter 또는 path variable 바인딩
- 성공 status
- 공통 응답 포맷
- 주요 response field
- validation 실패 또는 not found 같은 실패 응답
- 인증 필요 API라면 `X-User-Id`, `X-User-Role` 헤더 처리

### Application Service 변경

`ProductServiceTest` 또는 실제 클래스명에 맞는 application service 테스트를 추가하거나 수정한다.

확인 항목:

- business rule
- status 필터링
- category, sort, page, size 처리
- repository/client 호출 인자
- 예외 발생 조건
- response DTO 변환

### Domain 변경

domain model 또는 enum에 규칙이 생기면 domain 테스트를 추가한다.

확인 항목:

- 상태 변경
- 계산 규칙
- 불변 조건
- 잘못된 상태 전이 예외

### Persistence 변경

custom repository, Querydsl, 직접 작성한 query가 있으면 persistence 테스트를 추가한다.

확인 항목:

- DDL/entity mapping
- 검색 조건
- 정렬 조건
- pagination
- ON_SALE 등 노출 조건

단순 JpaRepository 기본 메서드만 사용할 때는 별도 repository 테스트를 강제하지 않는다.

## Product 공개 조회 API 최소 테스트

아래 API를 구현할 때는 최소한 Controller 테스트와 Service 테스트를 포함한다.

- `GET /api/v1/products`
- `GET /api/v1/products/{productId}`
- `GET /api/v1/products/{productId}/related`
- `GET /api/v1/products/{productId}/reviews`

최소 검증:

- 로그인 없이 성공
- 공통 응답 포맷
- FE가 사용하는 주요 필드
- category 필터
- sort 조건
- page/size 조건
- 없는 productId에 대한 에러
- 판매 중인 상품만 노출

## Build 기준

PR 전에는 `test` task만 단독 실행하지 않는다.
반드시 product-service 기준 build를 실행한다.

```powershell
cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service
.\gradlew.bat clean build --no-daemon
```

이 build는 아래를 포함한다.

- `product-service/src/main` 컴파일
- checkstyle
- `product-service/src/test` 컴파일
- 테스트 실행

CI와 유사하게 확인해야 하면 root `.github/workflows/reusable-build.yml`의 DB 환경변수와 맞춘다.

```powershell
$env:DB_HOST="localhost"
$env:DB_PORT="5432"
$env:DB_NAME="prompthub_test"
$env:DB_USERNAME="test"
$env:DB_PASSWORD="test"
.\gradlew.bat clean build --no-daemon
```

