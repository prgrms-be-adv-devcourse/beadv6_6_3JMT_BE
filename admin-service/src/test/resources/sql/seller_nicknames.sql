-- user-service 소유 "user" 테이블 재매핑 검증용 픽스처(판매자 닉네임 = user.name).
INSERT INTO "user" (user_id, name) VALUES
('cccccccc-0000-0000-0000-000000000001', '판매자A'),
('cccccccc-0000-0000-0000-000000000002', '판매자B');
