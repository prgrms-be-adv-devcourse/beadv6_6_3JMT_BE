# Client/Gateway 및 배치 패키지 컨벤션 정렬 실행 계획

> **목표:** 서비스 간 gRPC와 제3자 API의 용어를 분리하고, 클린 아키텍처의 계층 경계를 유지하면서 기능 응집도가 높은 패키지 구성을 허용한다.

## 변경 원칙

- 내부 서비스 동기 호출(gRPC·내부 REST)의 포트는 `application/client`, 구현은 `infrastructure/grpc/client` 또는 `infrastructure/rest/client`에 둔다.
- `Gateway`는 PG사처럼 시스템 경계 밖의 제3자 API 포트에만 사용한다.
- `presentation`·`application`·`domain`·`infrastructure`의 최상위 계층은 유지한다.
- Batch·Delivery처럼 독립된 흐름은 각 계층 안에서 기능 하위 패키지로 묶을 수 있다.
- 하나의 Chunk 파이프라인에만 쓰이는 Reader·Processor·Writer·Listener·내부 DTO는 `infrastructure/batch/<pipeline>`에 함께 둔다.
- 여러 파이프라인이 공유하는 배치 컴포넌트만 역할별 패키지에 둔다.

## Task 1: 공용 클린 아키텍처 규칙 수정

**File:** `.claude/rules/clean-architecture.md`

1. 최상위 `client` 계층과 `GatewayClient` 규칙을 제거한다.
2. 내부 서비스 Client 포트와 제3자 API Gateway 포트의 위치·이름·어댑터 위치를 구분한다.
3. gRPC server/client 패키지와 설정 위치를 명시한다.
4. 계층 내부의 기능 하위 패키지 허용 기준을 추가한다.
5. 배치 전용 컴포넌트의 파이프라인 단위 응집 규칙과 예시를 추가한다.

## Task 2: Payment 서비스 규칙 정렬

**File:** `payment-service/.claude/rules/architecture.md`

1. Toss `PaymentGateway`와 내부 Order gRPC Client를 서로 다른 경계로 표시한다.
2. 내부 Order gRPC 포트·어댑터의 목표 패키지를 새 공용 규칙에 맞춘다.
3. 현재 `OrderGateway` 이름은 기존 코드의 마이그레이션 대상임을 명시한다.

## Task 3: 정적 검증

1. `rg`로 최상위 `client`, 내부 gRPC `Gateway`, `GatewayClient`, 역할별 배치 패키지 강제 문구가 남았는지 확인한다.
2. `git diff --check`로 Markdown 공백 오류를 확인한다.
3. 변경 파일 diff와 `git status --short`를 확인한다.
4. 문서 변경만 있으므로 Gradle 테스트는 실행하지 않고 `UNVERIFIED`로 기록한다.
