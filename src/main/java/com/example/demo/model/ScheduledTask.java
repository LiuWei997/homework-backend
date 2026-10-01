package com.example.demo.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ScheduledTask(
        long id,
        String taskId,
        Instant executeAt,
        JsonNode payload,
        TaskStatus status,
        long lockVersion,
        long scheduleVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant triggeredAt,
        Instant nextAttemptAt,
        String leaseToken,
        Instant leaseUntil,
        int publishAttempts
) {
}
