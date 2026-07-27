# AI 상품 자동 검수 기능 설계 (issue #597)

## 배경 / 문제

판매자가 상품을 `submitForReview()`로 제출하면 `PENDING_REVIEW` 상태가 되지만, 현재
이 상태를 `ON_SALE`/`REJECTED`로 전이시키는 소비자가 없다 — 즉 검수 이후 단계가
아예 구현돼 있지 않다. AI를 이용해 이 검수를 자동화한다.

- 대상 issue: [#597](https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/issues/597)
  (본문은 템플릿만 있고 실제 요구사항 없음 — 이 문서가 요구사항 정의를 겸함)
- 관련 코드: `product-service/.../domain/model/entity/Product.java:218`
  (`submitForReview()`), `ProductStatus` enum(`DRAFT`, `PENDING_REVIEW`, `ON_SALE`,
  `REJECTED`, `STOPPED`, `SUPERSEDED`)

## 트리거 시점

판매자가 기존 `submitForReview()`를 호출하는 시점(= `PENDING_REVIEW` 진입 시점).
상품 최초 등록(`Product.create()`, `DRAFT`) 시점이 아니다. 이 흐름은 신규 등록과
버전 수정(major update) 양쪽에서 동일하게 재사용된다(`Product.update()`도 major면
`PENDING_REVIEW`로 감).

## 검수 기준

- 텍스트(name/description/content/tags) + 이미지(thumbnail/imageUrls) 둘 다 검수(vision).
- 거부 사유: **금지/불법 콘텐츠**(저작권 침해, 불법 복제, 음란물, 사기·허위 광고 등
  플랫폼 정책 위반), **스팸/저품질 콘텐츠**(의미 없는 텍스트, 상품과 무관한
  내용, 극단적으로 불친절한 설명).
- 이미지-텍스트 불일치 검증은 이번 범위에서 제외(YAGNI).
- **애매한(확신 낮은) 케이스는 반려(보수적 기본값).** `InspectionVerdict`에 별도
  confidence/보류 상태를 두지 않고 이진 판정(승인/반려)을 유지한다 — 프롬프트에
  "위반 여부가 확실하지 않으면 반려로 판단하라"는 지시를 명시해 LLM이 애매한
  경우 반려 쪽으로 기울게 한다. 위반 콘텐츠 노출 위험을 정상 상품 오탐 반려보다
  우선한다.

## 최종 판정

**AI 완전 자동** — 사람 개입 없음. 반려된 판매자는 기존 흐름 그대로(콘텐츠 수정
후 `submitForReview()` 재호출, `REJECTED -> PENDING_REVIEW`)로 재검수를 받는다.
이 재시도 경로가 이미 `Product.submitForReview()`에 존재하므로 별도 어필/재검토
API는 만들지 않는다.

## 아키텍처 — 이벤트 흐름

```
[판매자] --submitForReview--> product-service
  Product.submitForReview()               (기존, 변경 없음)
  └─ (신규) presign thumbnailUrl/imageUrls (StorageClient.generatePresignedDownloadUrl)
  └─ (신규) ProductEventProducer.publishReviewRequested(product, presigned URLs)
       topic=product-events (기존 토픽 재사용)
       eventType=PRODUCT_REVIEW_REQUESTED (신규)
       payload: productId, name, description, content, tags,
                thumbnailUrl(presigned), imageUrls(presigned 목록)

ai-service                                  (신규: 이 서비스 최초 Kafka 사용)
  ProductReviewRequestedConsumer (product-events 구독, group=ai-service)
  └─ ProductInspectionService
       └─ Spring AI OpenAI 클라이언트(vision) 호출
            입력: 텍스트 필드 + 이미지(Media, presigned URL 그대로 전달 — 직접
                  다운로드하지 않음)
            출력(구조화, .entity()): { approved: boolean, rejectionReason: string|null }
       └─ InspectionEventProducer.publish(productId, approved, rejectionReason)
            topic=ai-events (신규 토픽, ai-service가 최초로 소유하는 발행 토픽)
            eventType=PRODUCT_INSPECTION_COMPLETED (신규)
            aggregateType=PRODUCT, key=productId

product-service (신규 consumer)
  ProductInspectionResultConsumer (ai-events 구독, group=product-service)
  └─ ProductInspectionResultHandler
       상태가 아직 PENDING_REVIEW인 경우만 반영(멱등, 별도 처리이력 테이블 불필요):
         approved=true  -> Product.approve()          -> ON_SALE
         approved=false -> Product.reject(reason)      -> REJECTED, rejectionReason 저장
       이미 PENDING_REVIEW 아니면(중복/지연 이벤트) 로그만 남기고 스킵
```

## Presigned URL 처리

`Product` 엔티티의 `thumbnailUrl`/`imageUrls` 컬럼은 **raw S3 key**를 저장한다(응답
시점마다 `StorageClient.generatePresignedDownloadUrl(key)`로 매번 새로 presign하는
게 기존 패턴 — `ProductQueryService`, `SellerProductDetailResponse` 등 동일).
따라서 이벤트 payload에 엔티티 값을 그대로 넣으면 안 되고, **발행 직전에
presign해서 URL을 payload에 담는다.** 기존 GET presign 만료(30분,
`S3StorageAdapter.PRESIGNED_GET_EXPIRATION`)를 그대로 재사용한다.

리스크: 30분 내 AI가 처리 못 하면(재시도/DLT 지연) URL 만료. 정상 처리는 초 단위라
문제 없고, DLT 재처리 같은 예외적 상황에서만 발생 — 기존 시스템도 동일 TTL로
동작 중이므로 별도 대응 없이 감수한다(사용자 승인 완료).

## 실패 처리 — 별도 "실패 이벤트" 없음

OpenAI 호출 실패/구조화 파싱 실패는 `ProductReviewRequestedConsumer`에서 예외를
던지는 것으로 처리한다. 기존 `OrderEventConsumer`와 동일 패턴: 컨테이너
에러핸들러가 재시도(`FixedBackOff` 1s x2) 후 `product-events.DLT`로 보낸다.

- DLT 자체가 이미 실패 신호다(kafka-event.md 컨벤션, 정산 rollout 문서에서도
  "consumer lag/DLT 증가량"을 관측 항목으로 이미 사용).
- 실패 시 상품은 `PENDING_REVIEW`에 남는다 — 안전한 기본값. `ai-events`에 별도
  "실패" 이벤트를 발행해 product-service가 반영해봤자 갈 수 있는 안전한 상태가
  없다(자동 `ON_SALE`은 위험, `REJECTED`는 "정책 위반"이라는 거짓 신호).
- 트랜지언트 에러와 영구 실패를 구분해 이중 채널(이벤트+DLT)로 신호할 필요 없이
  재시도 -> DLT 흐름 하나로 충분하다.
- product-service 쪽 `ai-events` 소비 중 productId 없음/이미 결과 반영됨은
  정상 케이스로 보고 로그만 남기고 ack(DLT 아님).

## 컴포넌트 변경 상세

### product-service

| 파일 | 변경 |
|---|---|
| `infra/messaging/producer/ProductEventType` | `PRODUCT_REVIEW_REQUESTED` 추가 |
| `infra/messaging/producer/event/ProductReviewRequestedPayload` (신규) | productId, name, description, content, tags, thumbnailUrl(presigned), imageUrls(presigned) |
| `infra/messaging/producer/ProductEventProducer` | `publishReviewRequested(Product, presigned thumbnailUrl, presigned imageUrls)` 추가 |
| `application/service/ProductSellerService.submitForReview` | 상태 전이 후 presign + `publishReviewRequested` 호출 |
| `domain/model/entity/Product` | `approve()`, `reject(String reason)` 추가 — `supersede()`처럼 현재 상태가 `PENDING_REVIEW`일 때만 전이, 아니면 조용히 무시(멱등) |
| `infra/messaging/consumer/ai/AiEventType` (신규) | `PRODUCT_INSPECTION_COMPLETED` |
| `infra/messaging/consumer/ai/ProductInspectionResultConsumer` (신규) | `ai-events` 구독, `OrderEventConsumer`와 동일 패턴(얇게, usecase 호출) |
| `application/service/ProductInspectionResultHandler` (신규) | productId 조회 -> approve/reject 호출 |
| `infra/messaging/config/KafkaConfig` | `ai-events` 소비용 ConsumerFactory/ContainerFactory + DLT 에러핸들러 추가(기존 `order-events`/`product-events` 패턴과 동일) |

### ai-service (신규 domain: `inspection`)

```
inspection
  domain/model        InspectionVerdict (approved, rejectionReason)
  application/usecase  ProductInspectionUseCase
  application/service  ProductInspectionService — 이벤트 수신 -> AI 호출 -> 결과 발행 조율
  application/port      ProductInspectionAiPort (인터페이스)
  infra/client/openai   SpringAiProductInspectionAgent (ProductInspectionAiPort 구현)
                        InspectionPromptFactory — 금지/불법, 스팸/저품질 기준 +
                        애매하면 반려(보수적 기본값) 지시 포함 프롬프트
  infra/messaging/kafka/consumer  ProductReviewRequestedConsumer (product-events 구독)
  infra/messaging/kafka/producer  InspectionEventProducer, InspectionEventType, payload
  infra/messaging/kafka/config    KafkaConfig (신규 — 이 서비스 최초)
```

- vision 입력: presigned URL을 Spring AI `Media`에 그대로 전달(OpenAI가 URL 직접
  fetch 지원 — ai-service가 이미지를 별도로 다운로드하지 않는다). 이미지 없으면
  텍스트만으로 판정.
- 모델: 기존 설정된 `spring.ai.openai.model`(`application-local.yml`의
  `OPENAI_MODEL`, 기본 `gpt-5.6-luna`) 재사용. vision 미지원 모델일 경우 구현
  단계에서 별도 프로퍼티로 분리한다.
- 구조화 출력은 `.entity(InspectionVerdict.class)`로 강제, 파싱 실패 시 예외
  (위 "실패 처리" 참조).

## 공유 파일 변경 (승인 완료)

- 루트 `build.gradle` (134~145행) — Kafka 사용 서비스 목록에 `ai-service` 추가
  필요. 현재 주석 "Kafka 사용 서비스 4개(user-service는 이벤트 미사용)"에
  ai-service도 빠져 있음 — ai-service가 Kafka consumer+producer를 모두 갖는
  최초 사례가 된다.
- ai-service `application.yml`/`application-local.yml`에 `spring.kafka.*` 설정
  추가 필요(bootstrap-servers 등, product-service 설정과 동일 값 재사용).

## 테스트 계획

### product-service

- `ProductSellerServiceTest`: `submitForReview` 시 presign 후 이벤트 발행 검증.
- `Product` 도메인 테스트: `approve()`/`reject()` 상태 전이 + 잘못된 상태(이미
  `ON_SALE`/`REJECTED` 등)에서 무시(멱등) 검증.
- `ProductInspectionResultConsumer`/`ProductInspectionResultHandler` 테스트:
  승인/반려/중복(이미 처리된 상태) 케이스.

### ai-service

- `ProductInspectionServiceTest`: `ProductInspectionAiPort`를 mock으로 승인/반려
  분기, 이벤트 발행 검증.
- CI에는 실제 OpenAI 호출을 하지 않는다(정산 도메인과 동일 원칙,
  `ai-service/docs/settlement-assistant-rollout.md` 참고).

## 범위 밖 (이번에 다루지 않음)

- 이미지-텍스트 불일치 검증.
- 사람 개입(관리자 최종 승인) 플로우.
- 실패 시 별도 알림/이벤트(위 "실패 처리" 참조 — DLT로 충분).
