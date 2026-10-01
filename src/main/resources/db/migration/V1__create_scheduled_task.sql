CREATE TABLE scheduled_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(128) NOT NULL,
    run_timestamp TIMESTAMP(3) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(24) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 1,
    schedule_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    triggered_at TIMESTAMP(3) NULL,
    next_attempt_at TIMESTAMP(3) NULL,
    lease_token VARCHAR(36) NULL,
    lease_until TIMESTAMP(3) NULL,
    publish_attempts INT NOT NULL DEFAULT 0,
    mq_message_key VARCHAR(128) NULL,
    last_error VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_scheduled_task_task_id UNIQUE (task_id),
    CONSTRAINT chk_scheduled_task_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'PUBLISHING', 'PUBLISH_RETRY_WAIT',
                   'TRIGGERED', 'CANCELLED', 'FAILED')
    )
);

CREATE INDEX idx_scheduled_task_due
    ON scheduled_task (status, run_timestamp, id);
CREATE INDEX idx_scheduled_task_retry
    ON scheduled_task (status, next_attempt_at, id);
CREATE INDEX idx_scheduled_task_lease
    ON scheduled_task (status, lease_until);
CREATE INDEX idx_scheduled_task_created
    ON scheduled_task (created_at, task_id);
