# 구현 노트: 이벤트 발행 토픽 단일화

> 계획 문서: `12-unify-payment-topic.md`
> 구현 일자: 2026-07-07

## 커밋 이력

| 커밋 | 내용 |
|---|---|
| `4fc88e1` | docs: 토픽 단일화 구현 계획 문서 추가 |
| `35f88eb` | feat: 결제 이벤트 발행 토픽을 payment-events 단일 토픽으로 통합 |
| `413a8c5` | test: 통합 테스트 Kafka 토픽 참조를 payment-events로 교체 |

## 실행 결과

- `./gradlew :payment-service:test` — BUILD SUCCESSFUL (21s)
- 통합 테스트(`ConfirmPaymentIntegrationTest`, `RefundPaymentIntegrationTest`) 포함 전체 통과

## 트러블슈팅

### gradlew 위치

payment-service 단독 `gradlew`가 없어 모노레포 루트의 `gradlew`를 사용했다.

```bash
# 작동하지 않음 (payment-service 내에 gradlew 없음)
./gradlew :payment-service:test

# 올바른 실행 방법
/Users/anjinpyo/developments/dev-course/projects/beadv6_6_3JMT_BE/gradlew :payment-service:test
```

## 계획 대비 실제 변경 사항 차이

없음. 계획 문서의 내용을 그대로 따랐다.

## 잔여 작업 (payment-service 외부)

order-service 쪽 전환은 이 작업 범위 밖이다. order-service 팀이 별도로 진행한다.

| 파일 | 변경 내용 |
|---|---|
| `PaymentEventConsumer.java` | `TOPIC_APPROVED`, `TOPIC_REFUNDED` → `TOPIC = "payment-events"` |
| `KafkaIntegrationTest.java` | `@EmbeddedKafka` topics 배열에서 두 토픽 → `"payment-events"` |
| `PaymentEventConsumerIntegrationTest.java` | `kafkaTemplate.send("payment.approved/refunded", ...)` → `"payment-events"` |
