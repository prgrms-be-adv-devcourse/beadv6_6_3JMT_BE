-- 정산 V2 event 재생성용 파괴적 one-time reset이다.
-- 서비스가 소유한 정산 데이터와 Spring Batch metadata만 초기화한다.
TRUNCATE TABLE
    batch_step_execution_context,
    batch_step_execution,
    batch_job_execution_context,
    batch_job_execution_params,
    batch_job_execution,
    batch_job_instance,
    settlement_outbox_event,
    settlement_source_line,
    settlement_detail,
    settlement,
    settlement_batch;

ALTER SEQUENCE batch_job_instance_seq RESTART WITH 1;
ALTER SEQUENCE batch_job_execution_seq RESTART WITH 1;
ALTER SEQUENCE batch_step_execution_seq RESTART WITH 1;
