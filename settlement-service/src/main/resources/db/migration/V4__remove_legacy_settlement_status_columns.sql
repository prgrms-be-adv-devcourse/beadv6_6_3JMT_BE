-- Settlement 엔티티와 V2 이벤트에는 더 이상 사용하지 않는 legacy 상태 컬럼이다.
-- V3에서 데이터를 초기화했으므로, non-null legacy 컬럼이 현재 JPA insert를 막지 않도록 제거한다.
ALTER TABLE settlement
    DROP COLUMN payout_status,
    DROP COLUMN settlement_status;
