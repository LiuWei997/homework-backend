ALTER TABLE scheduled_task
    MODIFY mq_message_key VARCHAR(160) NULL;
