-- V1 baseline 작성 시 refresh_token.epoch 컬럼이 누락되어(RefreshToken 엔티티에는 존재)
-- Hibernate ddl-auto: validate가 "missing column [epoch]"로 계속 실패했다.
ALTER TABLE refresh_token
    ADD COLUMN epoch bigint NOT NULL DEFAULT 0;
