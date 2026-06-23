payment-service API 응답/예외 처리 규칙. 컨트롤러·ExceptionHandler·응답 형식 작업 시 따른다.

## API 응답 / 에러 처리

- **공통 응답 래퍼는 `common-module`** 의 것을 사용한다(서비스마다 새로 만들지 않는다).
- **도메인 에러 코드는 이 서비스가 소유**한다 — payment 도메인 전용 에러 코드/예외는 payment-service 내부에서 정의·관리.
- HTTP 예외 변환은 `interfaces.web`의 ExceptionHandler에서 처리. 도메인/application은 의미 있는 예외를 던지는 데 집중.
- 요청 유효성 검증은 `interfaces.web.dto.request`에서 `@Valid`로 수행 후 `command`로 변환.
