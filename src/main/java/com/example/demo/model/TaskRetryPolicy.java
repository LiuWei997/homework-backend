package com.example.demo.model;

import java.time.Duration;

public final class TaskRetryPolicy {
    public static final int MAX_RETRIES = 3;
    public static final int MAX_PUBLISH_ATTEMPTS = 1 + MAX_RETRIES;
    public static final int MAX_BACKOFF_EXPONENT = 4;
    public static final int MAX_BACKOFF_SECONDS = 16;

    private TaskRetryPolicy() { }

    public static Duration backoff(int failedAttempt) {
        int exponent = Math.min(MAX_BACKOFF_EXPONENT, Math.max(1, failedAttempt));
        return Duration.ofSeconds(1L << exponent);
    }

    public static TaskStatus statusAfterFailure(int failedAttempt) {
        return failedAttempt >= MAX_PUBLISH_ATTEMPTS ? TaskStatus.FAILED : TaskStatus.PUBLISH_RETRY_WAIT;
    }
}
