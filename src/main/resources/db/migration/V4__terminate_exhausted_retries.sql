UPDATE scheduled_task
SET status = 'FAILED', next_attempt_at = NULL,
    lease_token = NULL, lease_until = NULL,
    lock_version = lock_version + 1,
    last_error = 'Retry budget exhausted before upgrade'
WHERE status = 'PUBLISH_RETRY_WAIT' AND publish_attempts >= 4;
