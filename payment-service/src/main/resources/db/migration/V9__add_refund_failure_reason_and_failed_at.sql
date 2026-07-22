ALTER TABLE refund ADD COLUMN failure_reason text;
ALTER TABLE refund ADD COLUMN failed_at timestamptz;
