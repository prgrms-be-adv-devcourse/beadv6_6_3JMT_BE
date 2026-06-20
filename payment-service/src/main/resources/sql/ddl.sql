-- ENUM 타입 먼저 생성
CREATE TYPE payment_status AS ENUM (
    'READY',
    'REQUESTED',
    'PAID',
    'FAILED',
    'CANCELING',
    'CANCELED',
    'CANCEL_FAILED',
    'REFUNDING',
    'REFUNDED',
    'UNKNOWN'
    );

CREATE TABLE "payment" (
                           "id"                UUID            NOT NULL,
                           "order_id"          UUID            NOT NULL,
                           "user_id"           UUID            NOT NULL,
                           "pg_tx_id"          VARCHAR(100)    NOT NULL,
                           "status"            payment_status  DEFAULT 'READY' NOT NULL,
                           "payment_method"    VARCHAR(30)     NOT NULL,
                           "provider"          VARCHAR(30)     NOT NULL,
                           "is_test"           BOOLEAN         DEFAULT FALSE NOT NULL,
                           "total_amount"      INT             NOT NULL,
                           "product_amount"    INT             NOT NULL,
                           "discount_amount"   INT             DEFAULT 0 NOT NULL,
                           "approved_amount"   INT             NULL,
                           "canceled_amount"   INT             DEFAULT 0 NOT NULL,
                           "idempotency_key"   VARCHAR(255)    NOT NULL,
                           "failure_code"      VARCHAR(100)    NULL,
                           "failure_reason"    TEXT            NULL,
                           "cancel_reason"     TEXT            NULL,
                           "request_payload"   JSONB           NULL,
                           "response_payload"  JSONB           NULL,
                           "requested_at"      TIMESTAMPTZ     NULL,
                           "approved_at"       TIMESTAMPTZ     NULL,
                           "failed_at"         TIMESTAMPTZ     NULL,
                           "canceled_at"       TIMESTAMPTZ     NULL,
                           "refunded_at"       TIMESTAMPTZ     NULL,
                           "created_at"        TIMESTAMPTZ     DEFAULT NOW() NOT NULL,
                           "updated_at"        TIMESTAMPTZ     DEFAULT NOW() NOT NULL,

                           PRIMARY KEY ("id")
);

COMMENT ON COLUMN "payment"."id"                IS 'Payment 식별자';
COMMENT ON COLUMN "payment"."order_id"          IS '연결된 주문 ID';
COMMENT ON COLUMN "payment"."user_id"           IS '결제 요청 사용자 ID';
COMMENT ON COLUMN "payment"."pg_tx_id"          IS '토스페이먼츠 paymentKey. PG 콜백 중복 처리 방지 기준값';
COMMENT ON COLUMN "payment"."status"            IS '결제 상태. READY / REQUESTED / PAID / FAILED / CANCELING / CANCELED / CANCEL_FAILED / REFUNDING / REFUNDED / UNKNOWN';
COMMENT ON COLUMN "payment"."payment_method"    IS '결제 수단. 예) CARD';
COMMENT ON COLUMN "payment"."provider"          IS 'PG사 구분. 예) TOSS_PAYMENTS';
COMMENT ON COLUMN "payment"."is_test"           IS '테스트 결제 여부. TEST_PG 환경 구분용';
COMMENT ON COLUMN "payment"."total_amount"      IS '최종 결제 요청 금액 (product_amount - discount_amount)';
COMMENT ON COLUMN "payment"."product_amount"    IS '상품 원금액';
COMMENT ON COLUMN "payment"."discount_amount"   IS '할인 적용 금액. 기본값 0 (쿠폰·포인트 확장 시 사용)';
COMMENT ON COLUMN "payment"."approved_amount"   IS 'PG사가 실제 승인한 금액. 승인 전 NULL';
COMMENT ON COLUMN "payment"."canceled_amount"   IS '취소된 금액. 부분 취소 도입 시 활용. 기본값 0';
COMMENT ON COLUMN "payment"."idempotency_key"   IS '중복 결제 방지 키. 형식: pay-{order_id}';
COMMENT ON COLUMN "payment"."failure_code"      IS 'PG사 결제 실패 코드';
COMMENT ON COLUMN "payment"."failure_reason"    IS 'PG사 결제 실패 상세 사유';
COMMENT ON COLUMN "payment"."cancel_reason"     IS '구매자 취소 사유';
COMMENT ON COLUMN "payment"."request_payload"   IS 'PG사 결제 요청 원문 JSON. 분쟁·디버깅용';
COMMENT ON COLUMN "payment"."response_payload"  IS 'PG사 응답 원문 JSON. 분쟁·디버깅용';
COMMENT ON COLUMN "payment"."requested_at"      IS 'PG사에 결제 요청을 전송한 일시';
COMMENT ON COLUMN "payment"."approved_at"       IS 'PG사 결제 승인 완료 일시';
COMMENT ON COLUMN "payment"."failed_at"         IS 'PG사 결제 실패 일시';
COMMENT ON COLUMN "payment"."canceled_at"       IS 'PG사 취소 완료 일시';
COMMENT ON COLUMN "payment"."refunded_at"       IS 'PG사 환불 완료 일시';

-- ENUM 타입 먼저 생성
CREATE TYPE refund_status AS ENUM (
    'REQUESTED',
    'COMPLETED',
    'FAILED'
    );

CREATE TABLE "refund" (
                          "id"                UUID            NOT NULL,
                          "payment_id"        UUID            NOT NULL,
                          "order_product_id"  UUID            NULL,
                          "user_id"           UUID            NOT NULL,
                          "refund_amount"     INT             NOT NULL,
                          "reason"            TEXT            NULL,
                          "status"            refund_status   DEFAULT 'REQUESTED' NOT NULL,
                          "requested_at"      TIMESTAMPTZ     DEFAULT NOW() NOT NULL,
                          "completed_at"      TIMESTAMPTZ     NULL,
                          "created_at"        TIMESTAMPTZ     DEFAULT NOW() NOT NULL,
                          "updated_at"        TIMESTAMPTZ     DEFAULT NOW() NOT NULL,

                          PRIMARY KEY ("id"),
                          CONSTRAINT fk_refund_payment FOREIGN KEY ("payment_id") REFERENCES "payment"("id")
);

COMMENT ON COLUMN "refund"."id"                IS 'Refund 식별자';
COMMENT ON COLUMN "refund"."payment_id"        IS '연결된 Payment ID';
COMMENT ON COLUMN "refund"."order_product_id"  IS '부분 환불 대상 OrderProduct ID. 전체 환불 시 NULL';
COMMENT ON COLUMN "refund"."user_id"           IS '환불 요청 사용자 ID';
COMMENT ON COLUMN "refund"."refund_amount"     IS '환불 금액. 전체 환불 시 Payment.total_amount와 동일';
COMMENT ON COLUMN "refund"."reason"            IS '환불 사유';
COMMENT ON COLUMN "refund"."status"            IS '환불 상태. REQUESTED / COMPLETED / FAILED';
COMMENT ON COLUMN "refund"."requested_at"      IS '환불 요청 접수 일시';
COMMENT ON COLUMN "refund"."completed_at"      IS 'PG사 환불 처리 완료 일시. 미완료 시 NULL';