# 감사로그 Elasticsearch 파이프라인 구현 계획

write-only Postgres `audit_log`(#484/#539)를 Elasticsearch로 실시간 미러링해, 별도 조회 API를 만드는 대신 Kibana 검색으로 CS·장애분석·감사대응 조회 니즈를 충족한다.

---

## 배경 및 목표

`audit_log`는 #484/#539를 거치며 결제/환불의 6종 이벤트(`PAYMENT_REQUESTED`/`APPROVED`/`FAILED`, `REFUND_REQUESTED`/`COMPLETED`/`FAILED`)를 `order_id`·`failure_code`까지 포함해 write-only로 쌓고 있다. 두 계획 문서 모두 "조회 API는 스코프 밖"으로 명시적으로 미뤘다.

이번 작업은 그 조회 니즈를 REST 조회 API 신설이 아니라 **Elasticsearch 미러 + Kibana 검색**으로 해결한다. Postgres `audit_log`는 계속 유일한 source of truth로 남고, ES는 검색 편의를 위한 미러일 뿐이다.

기존 ELK 인프라(#625, `k8s/addons/elk`)는 SLF4J 애플리케이션 로그(`application-logs-*`)만 다루며 정형 감사 데이터를 다루지 않는다. 이번 작업은 그 옆에 별도 인덱스(`payment-audit-log-*`)를 추가한다.

## 설계 결정

**실시간 경로는 AFTER_COMMIT 이벤트 발행 + 신규 내부 전용 Kafka 토픽(`payment-audit-log`)을 쓴다.** `AuditLogEventListener`가 이미 같은 도메인 이벤트(`PaymentApprovedEvent` 등)를 구독해 `audit_log`에 저장하는 AFTER_COMMIT 리스너이므로, 저장 성공 직후 같은 리스너에서 Kafka로도 발행한다. DB를 실시간 경로의 폴링 소스로 쓰지 않은 이유는, "조회 API 대체"라는 목적상 검색 결과가 초 단위로 보여야 하는데 폴링은 태생적으로 주기만큼 지연되고, 그 지연을 줄이려 폴링을 촘촘히 하면 write 위주 테이블에 불필요한 조회 부하만 늘기 때문이다. 이 토픽은 `payment-events`(외부 서비스 계약)와 분리한다 — 감사 데이터를 외부 계약에 얹지 않는다는 #539의 원칙을 그대로 따른다. 발행은 저장이 성공한 건에 한해서만 한다(저장 실패 = 미러할 원본 자체가 없음).

**재조정(reconciliation)은 Logstash `http_poller`가 payment-service의 신규 내부 전용 엔드포인트(`GET /internal/audit-logs?since=`)를 5분 간격으로 오버랩 윈도우(최근 15분, `created_at >= now-15m`) 조회하는 방식으로 구현한다.** Kafka 발행이 드물게 실패해도 이 안전망이 최대 15분 내 보완한다. Logstash `jdbc` input(Postgres 자격증명·JDBC 드라이버를 Logstash에 심어야 함)이나 payment-service 자체 `@Scheduled` + ES 클라이언트 직접 호출(현재 ES를 아는 계층이 infra/ELK뿐인 경계를 깨고 애플리케이션이 ES 구현을 알아야 함) 대신 이 방식을 택했다. http_poller는 기존에 이미 검증된 HTTP-in 파이프라인 패턴(Fluent Bit→Logstash http input)과 같은 결의 메커니즘이고, Logstash에 새 자격증명·드라이버를 심을 필요가 없다. 오버랩 윈도우 방식이라 Logstash 쪽에 커서 상태(sincedb류)를 유지할 필요도 없어 재시작에도 안전하다.

**두 경로 모두 ES 문서ID를 `audit_log.id`(UUID)로 고정해 자연스러운 upsert 멱등성을 확보한다.** Kafka 경로가 먼저 색인하든 재조정 경로가 나중에 같은 건을 다시 색인하든 같은 문서로 병합되며 중복이 생기지 않는다.

**인덱스 네이밍은 기존 `application-logs-%{+YYYY.MM.dd}` / `gateway-access-%{+YYYY.MM.dd}` 패턴을 그대로 재사용한다(`payment-audit-log-%{+YYYY.MM.dd}`).** 이미 같은 Logstash 설정 파일에서 두 번 검증됐고 트러블슈팅 문서까지 쌓여 있어 운영 지식을 재사용할 수 있다. 요구사항이 "90일 후 삭제" 고정 기간이라 alias+rollover 같은 더 복잡한 메커니즘은 필요 없다 — delete-only ILM으로 충분하다. 단, 인덱스 날짜는 반드시 이벤트의 **`created_at` 기준**으로 계산해야 한다(수집 시각 아님) — 그래야 Kafka 경로와 재조정 경로가 같은 날짜의 같은 인덱스를 가리켜 upsert가 제대로 병합된다.

**보존 기간은 90일로 정한다.** Postgres가 영구 보존을 담당하므로 ES는 CS·분쟁대응 실무상 흔한 조회 윈도우만 커버하면 되고, 기존 ELK 클러스터 용량(10GiB, `application-logs-7d`와 병존) 부담도 최소화한다.

**내부 조회 엔드포인트(`/internal/audit-logs`)는 인증을 두지 않는다.** Gateway 라우팅 표에 이 경로가 없어 외부 진입이 불가능하고(클러스터 내부 신뢰 경계), 조회 전용이라 side effect가 없다. order→product 등 기존 내부 gRPC 호출도 같은 신뢰 경계를 전제로 인증을 별도로 얹지 않는다.

**Logstash 마스킹(REDACTED) 필터는 이 경로에 적용하지 않는다.** 기존 gateway/application 경로의 마스킹은 사용자 자유 입력(HTTP body, 헤더 등)이 로그에 실릴 위험을 막기 위한 것인데, `audit_log`의 모든 컬럼(`detail` 포함)은 서버가 만든 구조화 값이라 같은 위험이 없다.

## 아키텍처

```
[실시간 경로]
ConfirmPaymentService / RefundService
  → ApplicationEventPublisher.publishEvent(...)
      ↓ AFTER_COMMIT
  AuditLogEventListener
      → auditLogRepository.save(AuditLog)   [Postgres, 기존]
      → auditLogKafkaPublisher.publish(AuditLog)   [신규, 저장 성공시에만]
          ↓
      Kafka topic "payment-audit-log" (partition 1, retention 3d)
          ↓
      Logstash kafka input → payment-audit-log-%{+YYYY.MM.dd} (document_id=id, upsert)

[재조정 경로]
Logstash http_poller (5분 간격)
  → GET payment-service/internal/audit-logs?since=now-15m   [신규 엔드포인트, 무인증]
      → GetAuditLogsSinceUseCase → AuditLogRepository.findAllCreatedAfter(...)
  → 같은 인덱스에 동일 document_id로 upsert
```

## payment-service 코드 변경

- `infrastructure.messaging.config`: `PaymentTopic`(또는 별도 상수)에 `AUDIT_LOG = "payment-audit-log"` 추가, `NewTopic` 빈(partition 1, `retention.ms` 3일)
- `infrastructure.messaging`: `AuditLogKafkaPublisher` 신규 — `KafkaTemplate<String, AuditLogMessage>`, key=`auditLog.getId()`
- `infrastructure.messaging.dto`: `AuditLogMessage` record — `audit_log` 컬럼 그대로 미러(`id, orderId, entityType, entityId, eventType, actorId, newStatus, failureCode, detail, occurredAt, createdAt`)
- `infrastructure.persistence.AuditLogEventListener.save(...)`: 저장 성공 시 `auditLogKafkaPublisher.publish(auditLog)` 호출 추가(실패해도 감사로그 저장 자체엔 영향 없도록 try/catch 유지)
- `domain.repository.AuditLogRepository`: `List<AuditLog> findAllCreatedAfter(OffsetDateTime since, int limit)` 계약 추가
- `infrastructure.persistence.AuditLogRepositoryAdapter` / `AuditLogJpaRepository`: 위 계약 구현(`createdAt >= :since ORDER BY createdAt ASC LIMIT :limit`)
- `application.usecase.GetAuditLogsSinceUseCase` 신규 인터페이스
- `application.service` 구현체 신규 — `since` 파라미터로 리포지토리 호출, 안전 상한(예: 2000건) 적용
- `application.dto.result`: `AuditLogResult` record(응답 형태)
- `presentation`: `AuditLogQueryController` — `GET /internal/audit-logs?since={ISO8601}` → `AuditLogResponse` 목록 반환. Gateway 라우팅 표에 등록하지 않음(내부 전용).

## Elasticsearch / Logstash / Kibana (k8s/addons/elk, payment-service/ 밖 — 사용자 확인 완료)

- 매핑: `order_id`/`entity_id`/`actor_id`/`entity_type`/`event_type`/`new_status`/`failure_code` = keyword, `occurred_at`/`created_at` = date, `detail` = match_only_text
- ILM: `payment-audit-log-90d`(delete-only, 기존 `application-logs-7d`/`gateway-access-14d`와 같은 형태)
- Index template: `payment-audit-log-template`, `index_patterns: ["payment-audit-log-*"]`
- Logstash 파이프라인: 신규 `.conf` 파일에 kafka input + http_poller input 추가. **기존 `gateway-access.conf`의 무조건 실행 필터(redact ruby, 마지막 remove_field 등)가 신규 이벤트에도 적용되지 않도록, 신규/기존 양쪽 모두 `[@metadata][log_route]` 값으로 명시적 가드 필요** — 실제 Logstash 설정 diff는 구현 단계에서 확정
- Kibana: `payment-audit-log-*` Data View + 저장 검색(기본 컬럼: order_id, entity_type, event_type, new_status, failure_code, occurred_at) — 기존 `application-logs-kibana-bootstrap` Job과 같은 방식으로 부트스트랩

## 배포 (`.github/workflows/cd-selfhosted-kubernetes.yml` `deploy-elk` job)

- "ELK application log 수집 적용" 스텝의 `kubectl delete job` 목록에 `payment-audit-log-ilm-bootstrap payment-audit-log-kibana-bootstrap` 추가(Job은 spec 불변이라 재배포마다 삭제 후 재생성 필요 — 기존 두 Job과 같은 이유)
- 대응하는 `kubectl wait --for=condition=complete job/payment-audit-log-ilm-bootstrap`, `.../payment-audit-log-kibana-bootstrap` 라인 추가
- `fluent-bit` 관련 라인은 변경 없음(이번 변경은 Fluent Bit 경로와 무관)
- `logstash-http-credentials`/`kibana-encryption` 시크릿 확인 스텝도 변경 없음(http_poller는 무인증이라 신규 시크릿 불필요)
- elk↔prompthub 네임스페이스 간 NetworkPolicy 리소스가 레포에 없음을 확인함 — 크로스 네임스페이스 HTTP 호출을 막을 요소 없음
- Logstash 리소스(현재 request 100m/512Mi, limit 500m/768Mi)는 kafka+http_poller input 추가분을 배포 후 실측해 필요시 조정(선제적 추측치 반영 안 함)

## 실패 처리 / 안전성

- Kafka 발행 실패 → Postgres는 이미 커밋 완료 상태라 데이터 유실 없음, 재조정 경로가 최대 15분 내 보완
- 내부 엔드포인트는 조회 전용, side effect 없음, Gateway 미노출로 외부 진입 불가
- 기존 `gateway-access-*`/`application-logs-*` 파이프라인 설정·ILM·리소스는 이번 변경이 건드리지 않음

## 테스트 계획

- `AuditLogKafkaPublisherTest`: 발행 메시지 필드·key 검증(단위)
- `AuditLogEventListenerTest`: 6개 이벤트 각각 저장 성공 시 Kafka 발행 호출 검증 추가, 저장 실패 시 발행 스킵 검증
- `GetAuditLogsSinceUseCase`/Service 테스트: `since` 필터링, 상한 적용 검증
- `AuditLogQueryController` 통합 테스트(Testcontainers): `since` 파라미터로 필터링된 목록 반환 검증
- k8s/addons/elk: `deploy-elk` job의 `kubectl kustomize`/`--dry-run=server` 검증에 신규 리소스 포함 확인(별도 매니페스트 테스트 스위트는 레포에 없음 — CD workflow의 dry-run이 유일한 검증 수단)

## 트레이드오프 / 향후 과제

- **재조정 15분 윈도우**는 자정을 걸치는 극단적 케이스에서 전날 인덱스로 색인해야 할 이벤트를 놓칠 이론적 가능성이 있음(실질 발생 가능성 낮음, 별도 대응 없이 인지만 해둠)
- **Logstash 리소스 사이징**은 실측 후 조정 — 이번 계획에 구체 수치를 넣지 않음
- **`.claude/docs/events.md` 갱신**: `payment-audit-log` 토픽은 내부 전용(타 서비스 미소비)이라 기존 "발행/소비 매트릭스"와 성격이 다름 — 문서에 별도 절로 명시할지는 구현 단계에서 결정
- **Kafka 토픽 파티션/보존 값**(partition 1, retention 3d)은 현재 규모 기준 추정치 — 트래픽 증가 시 재검토
