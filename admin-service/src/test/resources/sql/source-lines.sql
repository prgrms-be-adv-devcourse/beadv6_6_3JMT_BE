-- admin 의 SettlementSourceLine 은 id·settlementId 만 재매핑한 subset 엔티티다.
-- 테스트 H2 스키마는 application-test.yml 안내대로 "재매핑 엔티티 기준"으로 생성되므로
-- (ddl-auto: create-drop, 재매핑 subset 컬럼만 포함), 이 fixture 도 그 2개 컬럼만 채운다.
INSERT INTO settlement_source_line (settlement_source_line_id, settlement_id)
VALUES ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111'),
       ('aaaaaaaa-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111');
