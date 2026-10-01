UPDATE scheduled_task
SET task_id = LOWER(task_id)
WHERE BINARY task_id <> BINARY LOWER(task_id);

ALTER TABLE scheduled_task
    MODIFY task_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD CONSTRAINT chk_scheduled_task_id_lowercase CHECK (
        BINARY task_id = BINARY LOWER(task_id)
    );
