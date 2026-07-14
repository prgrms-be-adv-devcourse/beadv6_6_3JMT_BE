#!/bin/bash
# ============================================================================
# ADR-0002: 스키마 분리 및 역할 기반 권한 관리
# ============================================================================
# 이 스크립트는 Docker entrypoint 실행 시 초기 스키마와 역할을 생성한다.
# 모든 문장은 멱등하게 작성되어 반복 실행돼도 안전하다.

set -e

# 환경변수 또는 기본값 설정
USER_SERVICE_PASSWORD="${USER_SERVICE_PASSWORD:-user_service_password}"
PRODUCT_SERVICE_PASSWORD="${PRODUCT_SERVICE_PASSWORD:-product_service_password}"
ORDER_SERVICE_PASSWORD="${ORDER_SERVICE_PASSWORD:-order_service_password}"
PAYMENT_SERVICE_PASSWORD="${PAYMENT_SERVICE_PASSWORD:-payment_service_password}"
SETTLEMENT_SERVICE_PASSWORD="${SETTLEMENT_SERVICE_PASSWORD:-settlement_service_password}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL

-- ── 스키마 생성 ──────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS user_service;
CREATE SCHEMA IF NOT EXISTS product_service;
CREATE SCHEMA IF NOT EXISTS order_service;
CREATE SCHEMA IF NOT EXISTS payment_service;
CREATE SCHEMA IF NOT EXISTS settlement_service;

-- ── 각 서비스용 전용 역할 생성 ──────────────────────────────────────────
DO \$\$
BEGIN
  CREATE ROLE user_service WITH LOGIN PASSWORD '$USER_SERVICE_PASSWORD' NOINHERIT;
EXCEPTION WHEN DUPLICATE_OBJECT THEN
  NULL;
END;
\$\$;

DO \$\$
BEGIN
  CREATE ROLE product_service WITH LOGIN PASSWORD '$PRODUCT_SERVICE_PASSWORD' NOINHERIT;
EXCEPTION WHEN DUPLICATE_OBJECT THEN
  NULL;
END;
\$\$;

DO \$\$
BEGIN
  CREATE ROLE order_service WITH LOGIN PASSWORD '$ORDER_SERVICE_PASSWORD' NOINHERIT;
EXCEPTION WHEN DUPLICATE_OBJECT THEN
  NULL;
END;
\$\$;

DO \$\$
BEGIN
  CREATE ROLE payment_service WITH LOGIN PASSWORD '$PAYMENT_SERVICE_PASSWORD' NOINHERIT;
EXCEPTION WHEN DUPLICATE_OBJECT THEN
  NULL;
END;
\$\$;

DO \$\$
BEGIN
  CREATE ROLE settlement_service WITH LOGIN PASSWORD '$SETTLEMENT_SERVICE_PASSWORD' NOINHERIT;
EXCEPTION WHEN DUPLICATE_OBJECT THEN
  NULL;
END;
\$\$;

-- ── 스키마 소유권 할당 ──────────────────────────────────────────────────
ALTER SCHEMA user_service OWNER TO user_service;
ALTER SCHEMA product_service OWNER TO product_service;
ALTER SCHEMA order_service OWNER TO order_service;
ALTER SCHEMA payment_service OWNER TO payment_service;
ALTER SCHEMA settlement_service OWNER TO settlement_service;

-- ── 기본 권한 설정: public 스키마 차단 ──────────────────────────────────
REVOKE ALL ON SCHEMA public FROM user_service;
REVOKE ALL ON SCHEMA public FROM product_service;
REVOKE ALL ON SCHEMA public FROM order_service;
REVOKE ALL ON SCHEMA public FROM payment_service;
REVOKE ALL ON SCHEMA public FROM settlement_service;

-- ── 타 스키마 접근 차단 ──────────────────────────────────────────────────
REVOKE ALL ON SCHEMA user_service FROM product_service, order_service, payment_service, settlement_service;
REVOKE ALL ON SCHEMA product_service FROM user_service, order_service, payment_service, settlement_service;
REVOKE ALL ON SCHEMA order_service FROM user_service, product_service, payment_service, settlement_service;
REVOKE ALL ON SCHEMA payment_service FROM user_service, product_service, order_service, settlement_service;
REVOKE ALL ON SCHEMA settlement_service FROM user_service, product_service, order_service, payment_service;

-- ── 자신의 스키마에 대한 권한 부여 ──────────────────────────────────────
GRANT USAGE ON SCHEMA user_service TO user_service;
GRANT CREATE ON SCHEMA user_service TO user_service;
GRANT ALL ON ALL TABLES IN SCHEMA user_service TO user_service;
GRANT ALL ON ALL SEQUENCES IN SCHEMA user_service TO user_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA user_service GRANT ALL ON TABLES TO user_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA user_service GRANT ALL ON SEQUENCES TO user_service;

GRANT USAGE ON SCHEMA product_service TO product_service;
GRANT CREATE ON SCHEMA product_service TO product_service;
GRANT ALL ON ALL TABLES IN SCHEMA product_service TO product_service;
GRANT ALL ON ALL SEQUENCES IN SCHEMA product_service TO product_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA product_service GRANT ALL ON TABLES TO product_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA product_service GRANT ALL ON SEQUENCES TO product_service;

GRANT USAGE ON SCHEMA order_service TO order_service;
GRANT CREATE ON SCHEMA order_service TO order_service;
GRANT ALL ON ALL TABLES IN SCHEMA order_service TO order_service;
GRANT ALL ON ALL SEQUENCES IN SCHEMA order_service TO order_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA order_service GRANT ALL ON TABLES TO order_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA order_service GRANT ALL ON SEQUENCES TO order_service;

GRANT USAGE ON SCHEMA payment_service TO payment_service;
GRANT CREATE ON SCHEMA payment_service TO payment_service;
GRANT ALL ON ALL TABLES IN SCHEMA payment_service TO payment_service;
GRANT ALL ON ALL SEQUENCES IN SCHEMA payment_service TO payment_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA payment_service GRANT ALL ON TABLES TO payment_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA payment_service GRANT ALL ON SEQUENCES TO payment_service;

GRANT USAGE ON SCHEMA settlement_service TO settlement_service;
GRANT CREATE ON SCHEMA settlement_service TO settlement_service;
GRANT ALL ON ALL TABLES IN SCHEMA settlement_service TO settlement_service;
GRANT ALL ON ALL SEQUENCES IN SCHEMA settlement_service TO settlement_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA settlement_service GRANT ALL ON TABLES TO settlement_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA settlement_service GRANT ALL ON SEQUENCES TO settlement_service;

-- ── search_path 설정: psql 직접 접속 시에도 기본 스키마 지정 ──────────────
ALTER ROLE user_service SET search_path = 'user_service', 'public';
ALTER ROLE product_service SET search_path = 'product_service', 'public';
ALTER ROLE order_service SET search_path = 'order_service', 'public';
ALTER ROLE payment_service SET search_path = 'payment_service', 'public';
ALTER ROLE settlement_service SET search_path = 'settlement_service', 'public';

-- ── prompthub 사용자 권한 유지: 모든 스키마 접근 가능 (superuser) ──────────
-- prompthub는 현재 superuser이므로 명시적 권한 설정 불필요. 모든 스키마 접근 가능.

EOSQL

echo "✅ 스키마, 역할, 권한 설정 완료"
