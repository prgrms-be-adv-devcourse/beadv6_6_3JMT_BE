-- 어드민 판매자 등록 신청 목록/승인/반려 쿼리 검증용 픽스처.
INSERT INTO seller_register (id, user_id, status, introduction, portfolio_url, submitted_at) VALUES
('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000001', 'PENDING', '마케팅 카피 전문', 'https://blog.example.com', '2026-07-10 09:00:00'),
('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000002', 'APPROVED', '이미지 생성 전문', NULL, '2026-07-05 09:00:00');

INSERT INTO seller_register_category (seller_register_id, category) VALUES
('22222222-0000-0000-0000-000000000001', '마케팅'),
('22222222-0000-0000-0000-000000000002', '이미지 생성');
