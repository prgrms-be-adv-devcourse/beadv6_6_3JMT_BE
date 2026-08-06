ALTER TABLE settlement_batch
    ADD COLUMN job_instance_id bigint;

ALTER TABLE settlement_batch
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

WITH extracted_execution_id AS (
    SELECT
        sb.batch_id,
        CASE
            WHEN length(suffix.value) < 19
                OR (length(suffix.value) = 19 AND suffix.value <= '9223372036854775807')
                THEN suffix.value::bigint
        END AS job_execution_id
    FROM settlement_batch sb
    CROSS JOIN LATERAL (
        SELECT substring(sb.batch_no FROM '([0-9]+)$') AS value
    ) suffix
    WHERE suffix.value IS NOT NULL
), candidates AS (
    SELECT extracted.batch_id, instance.job_instance_id
    FROM extracted_execution_id extracted
    JOIN batch_job_execution execution
      ON execution.job_execution_id = extracted.job_execution_id
    JOIN batch_job_instance instance
      ON instance.job_instance_id = execution.job_instance_id
    WHERE instance.job_name = 'settlementJob'
), unique_batch_candidates AS (
    SELECT batch_id, min(job_instance_id) AS job_instance_id
    FROM candidates
    GROUP BY batch_id
    HAVING count(*) = 1
), unambiguous_candidates AS (
    SELECT batch.batch_id, batch.job_instance_id
    FROM unique_batch_candidates batch
    JOIN (
        SELECT job_instance_id
        FROM unique_batch_candidates
        GROUP BY job_instance_id
        HAVING count(*) = 1
    ) instance ON instance.job_instance_id = batch.job_instance_id
)
UPDATE settlement_batch batch
SET job_instance_id = candidate.job_instance_id
FROM unambiguous_candidates candidate
WHERE batch.batch_id = candidate.batch_id;

CREATE UNIQUE INDEX uk_settlement_batch_job_instance_id
    ON settlement_batch (job_instance_id)
    WHERE job_instance_id IS NOT NULL;

ALTER TABLE settlement_batch
    DROP CONSTRAINT IF EXISTS settlement_batch_status_check;

ALTER TABLE settlement_batch
    ADD CONSTRAINT settlement_batch_status_check
    CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'RETRY_REQUESTED', 'CANCELLED'));
