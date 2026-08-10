# Product Service Swagger 규칙

product-service의 Swagger UI는 API Gateway를 통해 사용한다.

- Swagger 인증에는 Bearer JWT만 노출한다.
- Gateway가 주입하는 `X-User-Id`와 `X-User-Role`은 OpenAPI 문서에서 전역으로 숨긴다.
- Controller의 기존 `@RequestHeader` 계약은 유지하고 숨김 annotation을 메서드마다 반복하지 않는다.
- 모든 endpoint의 `@ApiResponses`와 모든 DTO 필드의 `@Schema`를 의무화하지 않는다.
- 외부 API 계약은 `docs/api-spec/product.md`, 오류 계약은 `ProductErrorCode`와 전역 예외 처리기를
  기준으로 관리한다.
- Swagger annotation은 실제 계약 이해에 필요한 경우에만 최소한으로 사용한다.

테스트 담당자는 역할에 맞는 테스트 계정으로 토큰을 발급받아 Swagger의 Authorize에 입력한다.
Gateway는 토큰을 검증하고 내부 사용자 헤더를 product-service 요청에 추가한다.
