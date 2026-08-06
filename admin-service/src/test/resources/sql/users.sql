-- 어드민 회원 목록/통계/상태변경 쿼리 검증용 픽스처.
-- H2 스키마는 application-test.yml 안내대로 admin 재매핑 엔티티(User) 기준으로 생성된다(ddl-auto: create-drop).
INSERT INTO "user" (id, name, email, status, created_at, updated_at) VALUES
('11111111-0000-0000-0000-000000000001', '김도윤', 'doyoon.kim@gmail.com', 'ACTIVE', '2026-07-01 09:00:00', '2026-07-01 09:00:00'),
('11111111-0000-0000-0000-000000000002', '이서아', 'seoah@example.com', 'ACTIVE', '2026-07-02 10:00:00', '2026-07-02 10:00:00'),
('11111111-0000-0000-0000-000000000003', '박준호', 'junho@example.com', 'BLOCKED', '2026-06-01 08:00:00', '2026-06-01 08:00:00');

INSERT INTO user_role (user_id, role) VALUES
('11111111-0000-0000-0000-000000000001', 'BUYER'),
('11111111-0000-0000-0000-000000000002', 'SELLER'),
('11111111-0000-0000-0000-000000000003', 'BUYER');
