# ADR-0001: gRPC 빌드 설정은 per-module로 유지한다

- 상태: accepted
- 날짜: 2026-07-05
- 관련: 루트 `build.gradle`, order/product/settlement/user-service의 gRPC 설정

## 컨텍스트

Gradle 멀티모듈 전환(#192) 이후 2차 공통 의존성 추출을 검토하면서, gRPC를 쓰는 4개 서비스
(order·product·settlement·user)의 `protobuf {}` 코드젠 블록이 문자 그대로 동일하다는 사실이
확인되어 루트 configure 블록으로의 추출이 제안되었다.

동시에 다음 사실도 확인되었다.

- Spring Boot 4.1 BOM은 `grpc-bom`(1.80.0)과 `protobuf-bom`(4.34.2)을 임포트하여
  모든 `io.grpc:*` / `com.google.protobuf:*` 의존성 버전을 직접 관리한다.
- Boot 4.1에는 `spring-boot-starter-grpc-server`뿐 아니라 `spring-boot-starter-grpc-client`도
  내장되어 있다. user-service의 서버 측(내장 스타터)이 팀이 지향하는 표준이고,
  order/product/settlement의 수동 grpc-java 런타임(netty-shaded 직접 관리)은 과도기 상태다.

## 결정

**gRPC/protobuf 빌드 설정(코드젠 블록 포함)은 루트로 추출하지 않고 각 모듈에 유지한다.**

- 팀 표준은 Boot 내장 spring-grpc(`spring-boot-starter-grpc-server`/`-client`) 방식이다.
  과도기 상태인 수동 grpc-java 런타임 구성을 루트 빌드에 고착시키지 않는다.
- 대신 `io.grpc:*`, `com.google.protobuf:protobuf-java`의 명시 버전은 제거하고 Boot BOM에
  위임한다. (user-service가 처음부터 쓰던 방식이 정석이었다)
- 루트 `ext`의 `grpcVersion`/`protobufVersion`은 BOM이 관리하지 못하는 protobuf 코드젠
  아티팩트(`protoc`, `protoc-gen-grpc-java`) 지정 용도로만 남기며, Boot 업그레이드 시
  BOM 버전과 함께 올린다.

## 결과

- `protobuf {}` 블록 4벌 중복은 감수한다. 3개 서비스를 spring-grpc로 마이그레이션하는 시점에
  코드젠 공통화를 재설계한다.
- 이후 아키텍처 리뷰에서 "gRPC 블록을 루트로 추출하라"는 제안은 이 ADR을 근거로 기각한다.
  (재검토 트리거: spring-grpc 마이그레이션 착수, 또는 Boot BOM의 gRPC 관리 방식 변경)
- 후속 이슈 후보: order/product의 spring-grpc 마이그레이션,
  서비스 간 .proto 계약 포크(패키지 분기·구버전 사본) 통합.

## 현황 업데이트 (2026-07-22)

작성 시점의 전제("gRPC 쓰는 4개 서비스: order·product·settlement·user", "user-service가
팀 표준 사례")가 이후 서비스 구조 변화로 깨졌다. 실제 현재 상태:

- **user-service: gRPC 완전 제거.** `build.gradle`에 grpc/protobuf 의존성이 하나도 없다 —
  서버도 클라이언트도 아니다. 이 ADR이 "팀 표준"으로 지목했던 케이스 자체가 사라졌다.
  (단 `config/src/main/resources/configs/user-service.yml`에는 `grpc.server.port` 값이
  여전히 남아있다 — 죽은 설정, 정리 필요.)
- **payment-service**: `spring-boot-starter-grpc-client`(Boot 네이티브) 클라이언트로
  gRPC 사용 중 — 작성 시점 목록(4개 서비스)에 아예 빠져 있었다.
- **settlement-service**: 이미 `spring-boot-starter-grpc-client`로 마이그레이션 완료
  (클라이언트, order 서버 대상). "과도기 상태"로 order/product와 묶여 서술됐던 것과 달리
  Boot 네이티브 전환이 끝난 상태.
- **order-service, product-service**: 여전히 수동 grpc-java 런타임(`grpc-stub`,
  `grpc-protobuf`, `netty-shaded` 직접 관리) — 남은 "과도기" 대상은 이 둘뿐이다.

**본 ADR의 핵심 결론("gRPC 빌드 설정을 루트로 추출하지 않는다")에는 영향 없음** — 다만
컨텍스트의 서비스 목록·표준 사례 서술은 위 표에 맞게 갱신 필요.
