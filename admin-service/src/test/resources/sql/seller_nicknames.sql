-- user-service 소유 "user" 테이블 재매핑 검증용 픽스처(판매자 닉네임 = user.name).
-- email/status는 이 테스트가 안 쓰지만, admin.user.domain.model.User가 같은 테이블을
-- NOT NULL로 매핑하고 있어(H2는 두 엔티티의 컬럼을 한 테이블로 병합) 채워야 insert가 통과한다.
INSERT INTO "user" (id, name, email, status) VALUES
('cccccccc-0000-0000-0000-000000000001', '판매자A', 'seller-a@example.com', 'ACTIVE'),
('cccccccc-0000-0000-0000-000000000002', '판매자B', 'seller-b@example.com', 'ACTIVE');
