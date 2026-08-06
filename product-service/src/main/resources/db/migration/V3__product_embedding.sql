--
-- product-service V3
--
-- 시맨틱 하이브리드 검색(#378)을 위한 상품 임베딩 저장.
--
-- Postgres가 임베딩의 원본이고 Elasticsearch는 검색용 사본이다(설계 D12). ES 인덱스가
-- 유실돼도 임베딩은 여기 남아 있어 전체 재색인이 OpenAI 재호출 없이 끝난다.
--
-- 선행: pgvector 확장이 설치된 이미지여야 한다(#586). 확장이 없으면 이 마이그레이션이
-- 실패하고 product-service가 기동하지 못한다.
--

CREATE EXTENSION IF NOT EXISTS vector;

-- text-embedding-3-small = 1536차원.
ALTER TABLE product ADD COLUMN IF NOT EXISTS embedding vector(1536);

-- 임베딩 원문(제목·태그·소개글·본문)의 해시. 값이 그대로면 OpenAI 호출을 건너뛴다.
ALTER TABLE product ADD COLUMN IF NOT EXISTS embedding_source_hash VARCHAR(64);

-- 부분 인덱스 — ON_SALE 행만 넣는다.
--
-- 상품은 parent_id row-chain으로 버전을 관리해서 family 하나에 여러 행이 있고, 그중
-- 노출되는 건 ON_SALE 한 행뿐이다. 전 버전을 색인하면 kNN 후보에 과거 버전이 섞이고
-- 인덱스도 불필요하게 커진다.
--
-- 연산자 클래스는 vector_cosine_ops — 검색·추천 모두 코사인 거리(<=>)를 쓴다.
CREATE INDEX IF NOT EXISTS idx_product_embedding_on_sale
    ON product USING hnsw (embedding vector_cosine_ops)
    WHERE status = 'ON_SALE';
