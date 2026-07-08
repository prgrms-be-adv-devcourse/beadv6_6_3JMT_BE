# 정산 서비스 아키텍처 문서

정산 서비스(settlement-service)의 구조·연동·설계 의사결정을 모은 폴더다.
"무엇을 주고받고, 왜 그렇게 했는지"를 다룬다. 코드 컨벤션(계층·패키지 규칙)은 여기가 아니라
`.claude/rules/clean-architecture.md` 에 있다.

## 연동 (조회 · 발행)

정산은 자기 DB만으로 끝나지 않는다. 매출/환불 원천은 배치 시점에 order 에서 gRPC 로 당겨와 쌓고,
판매자명·상품 수는 조회 실행 중에 다른 서비스를 동기로 호출해 당겨온다. (밖으로 내보내는 지급 완료
알림 발행은 추후·파이널. 어드민 모듈(admin-service)은 gRPC 가 아니라 DB 를 직접 바라보므로
rpc 계약이 없다 — 운영 조회·상태변경은 `seller_settlement`(유저 DB), 배치 예약·잡 상태는 정산 DB.
연동 카탈로그 §4.)

| 문서 | 내용 |
| --- | --- |
| [integration-catalog.md](integration-catalog.md) | 외부와 주고받는 데이터 카탈로그. 원천 데이터(매출/환불)를 gRPC pull 로 당겨오는 것과 참고 데이터(판매자 정보·상품 수) 동기 조회를 계약(proto·멱등키) 단위로 정리. **"무엇을"** 에 해당. |
| [kafka-messaging-design.md](kafka-messaging-design.md) | 정산의 Kafka 사용(지급 완료 발행 — 추후)을 클린아키텍처 룰에 맞춰 **어느 계층·패키지에 어떤 코드로 구현하는지**. 원천 수신은 gRPC pull 로 이관. **"어떻게"** 에 해당. |

## 구조 변화 (파이널)

파이널에서 서비스 구조가 어떻게 바뀌는지.

| 문서 | 내용 |
| --- | --- |
| [admin-module-separation.md](admin-module-separation.md) | 어드민 API(`SettlementController`)를 신설 admin-service 로 이관하는 설계. 배치·스케줄러·배치 테스트용 수동 실행은 정산에 잔류(판매자 API 는 유저 모듈로 이관 — #236). 어드민은 운영 조회·상태변경을 `seller_settlement`(유저 DB) 직접 접근, 배치는 정산 DB 예약 테이블 + 폴링으로 실행. |
| [../final-roadmap.md](../final-roadmap.md) | 파이널 고도화 전체 로드맵 — 어드민 분리, 수동 정산 → 정산 예약, AI 정산 어시스턴트(아이디어), 통계·대시보드. |

## 배포 (CI/CD)

빌드·배포가 어디서 어떻게 일어나는지.

| 문서 | 내용 |
| --- | --- |
| [deployment-ci-cd.md](deployment-ci-cd.md) | 프론트(Vercel)·백엔드(EC2) 배포 경로. GitHub Actions CI(빌드/테스트)와 EC2 self-hosted runner CD(빌드·env 주입·`docker compose up -d`), 도메인·CORS 설정. |

## 설계 의사결정 (trade-offs)

선택지를 비교하고 무엇을 왜 골랐는지 남긴 기록.

| 문서 | 다루는 결정 |
| --- | --- |
| [../trade-offs/internal-sync-transport.md](../trade-offs/internal-sync-transport.md) | 서비스 간 동기 호출을 REST(HTTP/JSON)로 할지 gRPC로 할지 |
| [../trade-offs/order-data-sourcing.md](../trade-offs/order-data-sourcing.md) | 정산 대상 주문 데이터(PAID·미정산 order_product)를 어떻게 수급할지 |
| [../trade-offs/settlement-batch-granularity.md](../trade-offs/settlement-batch-granularity.md) | 정산 배치를 판매자 단위 집계 3-step으로 둘지, 단일 chunk 스트리밍으로 둘지 |
| [../trade-offs/user-identity-propagation.md](../trade-offs/user-identity-propagation.md) | 인증/인가를 게이트웨이가 처리하고 각 서비스는 전달된 식별 정보(헤더)만 읽는 구조 |
| [../trade-offs/source-line-release-on-cancel.md](../trade-offs/source-line-release-on-cancel.md) | 정산 취소 시 묶인 원천소스(source line)를 행 단위 dirty checking으로 풀지, 단일 벌크 UPDATE로 풀지 |
| [../trade-offs/negative-settlement-carryforward.md](../trade-offs/negative-settlement-carryforward.md) | 환불 초과로 음수가 된 판매자 정산액을 어떻게 처리할지 — 다음 정산으로 이월(carry-forward) |
| [../trade-offs/admin-data-access.md](../trade-offs/admin-data-access.md) | 어드민(admin-service)이 정산 데이터를 직접 DB 커넥션으로 접근할지, gRPC 로 조회할지 — 직접 DB 채택 |

## 이 폴더 밖에 있는 관련 문서

- 설계 의사결정(trade-offs): [../trade-offs/](../trade-offs/)
- API 명세(프론트 연동): [../settlement-api-for-frontend.md](../settlement-api-for-frontend.md)
- 트러블슈팅: [../trouble-shooting/](../trouble-shooting/)
- 기능 설계·계획(스펙/플랜): [../superpowers/](../superpowers/)
- 코드 컨벤션(계층·도메인·예외·스타일 등): `.claude/rules/`
