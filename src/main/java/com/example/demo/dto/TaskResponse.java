package com.example.demo.dto;

import com.example.demo.model.ScheduledTask;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Locale;

public record TaskResponse(
        String taskId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "GMT+08:00") Instant executeAt,
        JsonNode payload,
        String status,
        long scheduleVersion,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "GMT+08:00") Instant createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "GMT+08:00") Instant updatedAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "GMT+08:00") Instant triggeredAt
) {
    public static TaskResponse from(ScheduledTask task) {
        return new TaskResponse(
                task.taskId(), task.executeAt(), task.payload(), task.status().name().toLowerCase(Locale.ROOT),
                task.scheduleVersion(), task.createdAt(), task.updatedAt(), task.triggeredAt());
    }
}
