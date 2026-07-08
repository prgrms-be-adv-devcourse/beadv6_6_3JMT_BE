## 🛠️ 설명 (Description)

공통 모듈의 Kafka `EventMessage` 구조 변경(Payload의 제네릭화 등)에 따라 `order-service`의 이벤트 송수신(Outbox, PaymentEventConsumer) 방식과 도메인 모델, 그리고 모든 관련 단위/통합 테스트를 성공적으로 마이그레이션 및 정상화했습니다.

## 📄 설계 문서 (Design Document)

- 변경된 요구사항 및 기존 이슈 논의: 관련된 PR(#239 등) 참조

## ✅ 테스트 계획 (Test Plan)

- `order-service` 내부의 모든 단위 테스트 및 통합 테스트 수행 (총 253개 테스트 통과)
- **통합 테스트**: `OutboxRelayIntegrationTest`, `PaymentEventConsumerIntegrationTest`를 통해 실제 Embedded Kafka 환경에서 JSON 직렬화/역직렬화 및 DLT 라우팅 정상 동작 검증 완료
- **단위 테스트**: `PaymentApprovedProcessorTest`, `PaymentRefundedProcessorTest` 등으로 분리하여 비즈니스 로직 테스트 커버리지 유지

## 📝 변경 사항 요약 (Summary)

- **Outbox & ProcessedEvent 도메인 리팩터링**: 팩토리 메서드 패턴 도입 및 불필요한 엔티티 구조 단순화
- **KafkaConsumer 직렬화 버그 픽스**: `PaymentEventConsumer`가 타입 헤더 없이도 원시 JSON 문자열을 수신하여 `ObjectMapper`로 수동 역직렬화 및 Payload 유효성을 자체 검증하도록 개선 (DLT 라우팅 완벽 대응)
- **OutboxRelay 직렬화 중복 방지**: 페이로드가 이미 `String`일 때 `JacksonJsonSerializer`가 이중으로 감싸지 않도록 전용 `KafkaTemplate<String, String>` 빈 구성 추가
- **테스트 코드 마이그레이션**: 아키텍처(Handler/Processor 분리) 및 타입 변경에 맞춰 기존 테스트 일괄 리팩터링 및 신규 검증 추가

## 🔗 관련 이슈 (Related Issues)

- Closed #239 (또는 관련된 이슈 번호)

## ☑️ 체크리스트 (Checklist)

- [x] 클린아키텍처: 의존성 방향(presentation→application→domain←infrastructure)·포트&어댑터·계층 책임 준수
- [x] 도메인 모델: 비즈니스 setter 금지·상태변경은 도메인 메서드·Lombok 허용 범위 준수
- [x] Controller/예외: 얇은 컨트롤러(Repository 직접 접근 금지)·커스텀 예외·전역 예외 핸들러
- [x] 코드 스타일: 네이밍 케이스·와일드카드 import 금지·빈 catch 금지
- [x] Swagger: 표현계층 한정·@Operation/@ApiResponses/@Schema 명시
- [x] Git 컨벤션: 커밋 메시지(`<타입>: <내용>`)·브랜치 명명 규칙
- [x] 보안: .env·시크릿 파일 미커밋·코드 내 하드코딩 시크릿 없음·민감정보 로깅/노출 없음·.gitignore 누락 없음

## 👀 리뷰어를 위한 참고 사항 (Notes for Reviewers)

- 기존 `JacksonJsonDeserializer`의 타입 헤더 의존성 문제를 해결하기 위해 `order-service`의 Consumer 설정이 String 수신 후 수동 파싱으로 변경되었습니다.
- 관련 마이그레이션 내용을 확인하실 때 `KafkaConfig.java` 및 `PaymentEventConsumer.java`를 중점적으로 확인해주세요.

## ➕ 추가 정보 (Additional Information)
- 모든 단위 및 통합 테스트가 성공적으로 통과되었습니다.
