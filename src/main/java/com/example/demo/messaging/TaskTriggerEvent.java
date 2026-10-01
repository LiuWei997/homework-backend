package com.example.demo.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record TaskTriggerEvent(
        String eventId,
        String eventType,
        String taskId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC") Instant executeAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC") Instant publishAttemptedAt,
        JsonNode payload
) {
}
