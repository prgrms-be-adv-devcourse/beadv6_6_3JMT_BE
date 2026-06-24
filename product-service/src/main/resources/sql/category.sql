-- product-service DB에서 실행
INSERT INTO category (id, parent_id, code, name, icon, display_order, created_at, updated_at)
VALUES
    ('11111111-0000-0000-0000-000000000001', NULL, 'image',     '이미지 생성', 'image',          1, NOW(), NOW()),
    ('11111111-0000-0000-0000-000000000002', NULL, 'writing',   '글쓰기',     'pen-line',       2, NOW(), NOW()),
    ('11111111-0000-0000-0000-000000000003', NULL, 'coding',    '코딩',       'code-xml',       3, NOW(), NOW()),
    ('11111111-0000-0000-0000-000000000004', NULL, 'marketing', '마케팅',     'megaphone',      4, NOW(), NOW()),
    ('11111111-0000-0000-0000-000000000005', NULL, 'chatbot',   '챗봇',       'message-circle', 5, NOW(), NOW()),
    ('11111111-0000-0000-0000-000000000006', NULL, 'data',      '데이터',     'bar-chart-3',    6, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
