# 환불 API 테스트 및 트러블슈팅 결과

## 작업 개요

`06-local-api-test-plan.md`에 정의된 TC-REF-01 ~ TC-REF-05 환불 흐름 수동 테스트 수행.
테스트 과정에서 Toss 취소 API 엔드포인트 오류, Jackson 3.x 파싱 오류, Kafka 타임존 오류를 순차 발견·수정.

---

## 수정된 버그

### BUG-1: Toss 취소 API 엔드포인트 오타

| 항목 | 내용 |
|---|---|
| 파일 | `infrastructure/external/toss/TossPaymentGateway.java` |
| 증상 | `POST /v1/payments/{paymentKey}/cancels` → 404 Not Found |
| 원인 | Toss 취소 API의 실제 경로는 `/cancel` (단수)인데 `/cancels` (복수)로 잘못 구현 |
| 수정 | `.uri("/payments/{paymentKey}/cancels", pgTxId)` → `.uri("/payments/{paymentKey}/cancel", pgTxId)` |

> **트러블슈팅 과정**: 처음엔 Toss 시크릿 키 오류, 결제위젯 연동 키 유형 불일치, mId `tvivarepublica` 공용 머천트 제약 등 여러 가설을 순차 검증했으나 모두 기각. 키 재발급 후에도 동일 404가 반복되어, 마지막으로 엔드포인트 URL 자체를 확인하여 단순 오타임을 확인.

---

### BUG-2: Jackson 3.x `parseError()` 파싱 오류

| 항목 | 내용 |
|---|---|
| 파일 | `infrastructure/external/toss/TossPaymentGateway.java` |
| 증상 | Toss 에러 응답 파싱 시 `MismatchedInputException` 미처리 → 스택 트레이스 노출 |
| 원인 | Jackson 3.x에서 `MismatchedInputException`이 `RuntimeException`을 상속(Jackson 2.x는 `IOException` 상속). `catch (IOException)`으로는 잡히지 않음 |
| 수정 | `objectMapper.readValue(body, TossErrorResponse.class)` 방식 → `objectMapper.readTree(body)`로 `JsonNode` 파싱으로 전환. `message` 필드가 String/Object 양쪽을 처리 |

**수정 후 `parseError()` 구현:**
```java
private TossErrorResponse parseError(ClientHttpResponse resp) {
    try {
        String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode node = objectMapper.readTree(body);
        String code = node.path("code").asText("UNKNOWN");
        JsonNode messageNode = node.path("message");
        String message = messageNode.isObject() ? messageNode.toString() : messageNode.asText("PG사 오류");
        return new TossErrorResponse(code, message);
    } catch (IOException e) {
        return new TossErrorResponse("UNKNOWN", "PG사 응답 파싱 실패");
    }
}
```

---

### BUG-3: Kafka 이벤트 타임존 UTC → KST 미변환

| 항목 | 내용 |
|---|---|
| 파일 | `infrastructure/messaging/KafkaPaymentEventPublisher.java` |
| 증상 | `approvedAt`, `refundedAt` 필드가 UTC 오프셋(`+00:00`)으로 발행 |
| 원인 | `OffsetDateTime`을 그대로 `toString()` 처리 |
| 수정 | `ZoneOffset KST = ZoneOffset.ofHours(9)` 상수 추가, `.withOffsetSameInstant(KST).toString()`으로 KST 변환 후 발행 |

---

## 테스트 결과

### 결제 승인 (`TC-PAY-01 ~ TC-PAY-04`)

| TC | 설명 | HTTP | 결과 |
|---|---|---|---|
| TC-PAY-01 | 정상 결제 승인 | 200 | ✅ PAID, Kafka `payment.approved` 발행 확인 |
| TC-PAY-02 | 중복 결제 방지 | 409 | ✅ `PAY002` |
| TC-PAY-03 | BUYER 역할 없음 | 403 | ✅ `PAY007` |
| TC-PAY-04 | 입력값 오류(amount=0) | 400 | ✅ `V001` |

### 환불 (`TC-REF-01 ~ TC-REF-05`)

| TC | 설명 | HTTP | 결과 |
|---|---|---|---|
| TC-REF-01 | 정상 환불 | 202 | ✅ REFUNDED, Kafka `payment.refunded` 발행 확인 |
| TC-REF-02 | 이미 환불된 건 재요청 | 400 | ✅ `PAY004` |
| TC-REF-03 | 타인 결제 건 환불 시도 | 403 | ✅ `PAY006` |
| TC-REF-04 | 존재하지 않는 paymentId | 404 | ✅ `PAY005` |
| TC-REF-05 | BUYER 역할 없음 | 403 | ✅ `PAY007` |

---

## 환경 특이사항

- Toss 테스트 계정 `mId: tvivarepublica`는 사업자 미등록 개발자 계정의 공용 테스트 mId이나, **결제 승인 및 취소 모두 정상 동작**함이 BUG-1 수정 후 확인됨.
- 결제위젯 연동에서 "API 개별 연동 클라이언트 키"(`test_ck_...`)를 사용해도 결제 승인·취소 전 라이프사이클 모두 정상 동작.
- `ddl-auto: create-drop` 설정으로 서비스 재기동 시 DB 스키마가 재생성되므로 재기동 후 새 결제부터 테스트해야 한다.
