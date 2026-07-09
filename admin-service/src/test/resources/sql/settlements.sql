-- admin 은 운영 단일 진실 seller_settlement(user DB) 를 재매핑한다. 상태는 SettlementDisplayStatus 7값 단일.
-- 테스트 H2 스키마는 application-test.yml 안내대로 admin 재매핑 엔티티 기준으로 생성된다(ddl-auto: create-drop).
INSERT INTO seller_settlement (seller_settlement_id, settlement_id, seller_id, period_start, period_end,
                               product_count, total_amount, settlement_total_amount, fee_total_amount,
                               calculated_at, status)
VALUES ('bbbbbbbb-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', '2026-06-01', '2026-06-30', 3,
        540000.00, 459000.00, 81000.00, '2026-07-01T02:00:00', 'WAITING'),
       ('bbbbbbbb-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333',
        '22222222-2222-2222-2222-222222222222', '2026-05-01', '2026-05-31', 5,
        900000.00, 765000.00, 135000.00, '2026-06-01T02:00:00', 'PAID'),
       ('bbbbbbbb-0000-0000-0000-000000000003', '44444444-4444-4444-4444-444444444444',
        '55555555-5555-5555-5555-555555555555', '2026-06-01', '2026-06-30', 1,
        100000.00, 85000.00, 15000.00, '2026-07-01T02:00:00', 'APPROVED');
