payment-service API 응답/예외 처리 규칙. 컨트롤러·ExceptionHandler·응답 형식 작업 시 따른다.

> 요청 검증(`@Valid`)·공통 응답 래퍼 등 팀 공통 규칙은 루트
> `../../../.claude/rules/controller-exception.md`를 따른다. 이 문서는 그중
> payment-service에만 해당하는 구체화·추가 규칙만 기재한다.

## API 응답 / 에러 처리

- **도메인 에러 코드는 이 서비스가 소유**한다 — payment 도메인 전용 에러 코드/예외는 payment-service 내부에서 정의·관리.
- **HTTP 예외 변환은 `presentation`의 ExceptionHandler에서 처리한다.** 루트 표준(`global/exception`)과
  다른 위치인데, 아직 통일되지 않은 상태다(사유는 `architecture.md` 참고). 도메인/application은 의미 있는
  예외를 던지는 데 집중한다.
- **역할(X-User-Role) 기반 인가는 하지 않는다** — gateway 이관(#293 패턴). 대신 **본인 확인(ownership)은
  Controller/Service에서 `X-User-Id` 기반으로 직접 검증**한다(예: 본인 주문·본인 결제 건 확인).
