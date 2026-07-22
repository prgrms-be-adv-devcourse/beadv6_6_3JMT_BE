# AI 정산 어시스턴트 롤아웃

이 문서는 Seller용 `/api/v2/ai/settlement/**`를 운영에 처음 연결할 때의 체크리스트다. CI에서는 실제 OpenAI 호출을 하지 않는다. public ingress를 통한 live smoke는 운영자가 명시적으로 정확히 한 번만 실행한다.

## 배포 전 확인

1. Settlement V2 데이터 파이프라인, User gRPC 조회, AI service, 플랫폼 롤아웃 계획의 unit/integration test가 모두 통과했는지 확인한다. 테스트와 CI에는 OpenAI credential을 주입하지 않는다.
2. `ai-secret`에 실제 `OPENAI_API_KEY`, `AI_USER_GRPC_TOKEN` 두 key를 준비한다. Secret 입력 파일은 mode `600`으로 제한하고 저장소, shell history, CI log에 값을 남기지 않는다. Slack key는 아직 추가하지 않는다.
3. 먼저 유지된 `settlement-events` record와 User consumer group의 committed offset/lag, `settlement-events.DLT` 잔량을 점검한다. 이 단계에서는 User listener를 `false`로 유지한다. record 본문을 로그로 출력하지 않고 partition별 시작·끝 offset, committed offset와 건수만 기록한다. 기존 record 삭제·offset 이동·replay가 필요하면 대상 범위를 먼저 승인받고, 승인 전에는 변경하지 않는다.
4. User V3 migration을 적용해 read model schema가 준비됐는지 확인하되 listener는 계속 `false`로 둔다. AI와 User가 같은 internal token을 참조해야 한다.
5. Settlement V3 image를 적용한 `settlement-weekly` CronJob을 실행하고 해당 Job이 성공 종료할 때까지 기다린다. Flyway V3 완료, 정산·outbox·Spring Batch metadata 초기화와 Job 성공을 확인한다. image 교체나 CronJob 생성만으로는 완료로 간주하지 않는다.
6. 승인된 과거 주차 목록만 backfill하고, producer가 `payloadVersion=2`를 발행했는지 본문 노출 없는 집계 건수와 offset 증가량으로 확인한다. 승인되지 않은 주차를 임의로 재생성하지 않는다. V1·unknown version이 새로 관찰되거나 backfill이 실패하면 여기서 중단한다.
7. 3→6의 순서가 모두 완료된 뒤에만 `USER_SETTLEMENT_KAFKA_LISTENER_ENABLED=true`로 바꾼다. consumer lag가 줄고 DLT가 증가하지 않으며, User read model의 V2 부모·Detail 건수와 합계가 Settlement 결과와 일치하는지 확인한다. 이 gate를 통과하기 전에 AI를 rollout하지 않는다.
8. Config, User, AI, API Gateway image가 해당 release SHA인지 확인한 뒤 순서대로 rollout 상태를 확인한다. AI보다 User가 먼저, API Gateway가 나중에 배포되어야 한다. `settlement-events.DLT` 발행은 계속 유지하며 `SETTLEMENT_DLT_SLACK_WEBHOOK_URL`이 비어 있으면 Slack notifier만 disabled인 것이 정상이다.

```bash
kubectl -n prompthub get secret ai-secret
kubectl -n prompthub rollout status deployment/config --timeout=10m
kubectl -n prompthub rollout status deployment/user-service --timeout=10m
kubectl -n prompthub rollout status deployment/ai-service --timeout=10m
kubectl -n prompthub rollout status deployment/apigateway --timeout=10m
kubectl -n prompthub get deployment config user-service ai-service apigateway \
  -o custom-columns=NAME:.metadata.name,READY:.status.readyReplicas,IMAGE:.spec.template.spec.containers[0].image
```

명령 출력에 질문, 답변, actor ID, token 또는 정산 payload를 남기지 않는다.

## public ingress live smoke 한 건

Seller bearer token은 화면에 표시하거나 shell history에 literal로 입력하지 않는다. 아래처럼 숨김 입력으로 환경변수를 준비한 뒤 script를 한 번만 실행한다. script 한 번은 질문 `POST`를 정확히 한 건 생성한다. 실패해도 원인을 확인하기 전에 자동 재시도하지 않는다.

```bash
export AI_GATEWAY_BASE_URL='https://<public-gateway-host>'
read -r -s -p 'Seller bearer token: ' AI_SMOKE_BEARER_TOKEN
printf '\n'
export AI_SMOKE_BEARER_TOKEN
bash scripts/smoke-ai-settlement.sh
unset AI_SMOKE_BEARER_TOKEN
```

기본 질문은 `지난달 정산 매출, 환불, 수수료, 지급액을 요약해줘`다. script는 `202/RUNNING`, UUID v4 run ID, SSE `text/event-stream`, 125초 내 `done`, 20초 이상 실행 시 terminal 전 heartbeat, GET current의 최종 assistant 답변 정책을 확인한다. `failed` 또는 `cancelled`는 실패다.

질문, token, HTTP/SSE raw body와 답변 전문은 기본 출력하지 않는다. 로컬 운영자가 답변을 직접 확인해야 할 때만 `AI_SMOKE_PRINT_ANSWER=true`를 명시할 수 있으며 CI에서는 이 값을 설정하지 않는다. `AI_SMOKE_QUESTION`으로 질문을 바꾸더라도 2,000자를 넘기지 않는다.

### Tool·User gRPC 관측 gate

script의 HTTP/SSE 검증만 통과한 것은 전체 rollout smoke 성공이 아니다. 위 한 건을 실행하기 직전·직후의 안전한 metric 증가량 또는 같은 실행 시간대의 구조화 로그로 다음 두 가지를 모두 확인한다.

- `ai.tools.calls`가 1 이상 증가했거나, 동일 run의 tool 이름·순서·latency·success/failure만 담은 로그가 1건 이상 있다.
- `ai.user.grpc.calls`가 1 이상 증가했거나, 동일 run의 RPC 이름·latency·gRPC status만 담은 로그가 1건 이상 있다.

Tool 호출은 있지만 User gRPC 호출이 없거나 그 반대인 경우는 실패다. SSE `done`과 OpenAI usage만으로 두 호출을 추정하지 않는다. 증거에는 runId를 correlation key로 사용할 수 있지만 질문·답변·금액·Tool payload·gRPC request/response·actor/seller ID·token 값은 포함하지 않는다. 두 증거 중 하나라도 없으면 자동으로 두 번째 POST를 보내지 말고 관측 설정과 호출 경로를 먼저 점검한다.

smoke 직후 OpenAI usage dashboard에서 방금 실행한 요청 한 건의 token과 비용만 확인한다. dashboard 수치나 운영 로그를 공유할 때도 위 제외 기준을 지킨다.

## 최초 image digest 고정

첫 AI image가 GHCR에 push되고 release SHA tag로 실제 rollout된 뒤 registry에서 그 image의 64자리 SHA-256 digest를 조회한다. tag가 가리키는 digest와 실제 Pod image digest가 일치하는지 확인한 다음, AI base Deployment의 `image`를 완전한 `ghcr.io/.../prompthub-ai-service@sha256:<64-hex>` reference로 고정한다. digest를 알기 전에는 `latest`, 임의 placeholder 또는 추측한 digest를 manifest에 넣지 않는다.

runtime CD overlay는 base digest를 현재 short SHA tag로 치환해 release를 배포한다. base digest 고정 변경도 정적 manifest 검증을 거쳐 CD로 적용한다.

## 24시간 관찰

배포 24시간 뒤 Redis DB 1의 `ai:settlement:*` 표본이 마지막 유효 질문 또는 완결 답변 기준 24시간 TTL을 갖는지 확인한다. key 이름과 actor ID는 terminal이나 운영 기록에 출력하지 않고 TTL 값만 확인한다. 다음 항목도 함께 관찰한다.

- AI run timeout, OpenAI 429/5xx, User gRPC deadline과 `SETTLEMENT_DATA_UNAVAILABLE`
- Pod CPU/heap, 실행 동시성 4, queue 0이 만드는 거절률
- SSE heartbeat 간격과 public ingress의 buffering 여부
- User Settlement V2 consumer lag와 DLT 증가량

## 기능 차단과 복구

긴급 차단은 AI 기능 flag만 내린다.

```bash
kubectl -n prompthub set env deployment/ai-service AI_SETTLEMENT_CHAT_ENABLED=false
kubectl -n prompthub rollout status deployment/ai-service --timeout=10m
```

위 명령은 긴급 운영 예시다. 정상적인 영구 변경은 manifest의 값을 `false`로 바꾸고 CD로 적용한다. Redis data, User read model, `settlement-events` 또는 DLT topic은 삭제하지 않는다. 원인을 해결하고 test와 배포 검증을 다시 통과한 뒤 flag를 manifest로 복구한다.
