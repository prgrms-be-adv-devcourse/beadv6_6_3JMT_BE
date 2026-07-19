-- OrderSnapshot 로컬 캐시 제거 — 매 결제 승인 요청마다 order-service gRPC로 직접 조회하는 구조로 전환(#396).
DROP TABLE IF EXISTS order_snapshot;
