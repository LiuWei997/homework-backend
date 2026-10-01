package com.example.demo.util;

import com.example.demo.exception.TaskApiException;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TaskTimeCodec {
    private static final Pattern FRACTION = Pattern.compile("\\.(\\d+)(?:Z|[+-]\\d{2}:\\d{2}(?::\\d{2})?)$");

    private TaskTimeCodec() {
    }

    public static Instant parseFutureInstant(String value, Instant now) {
        if (value == null) {
            throw invalidTime();
        }
        Matcher matcher = FRACTION.matcher(value);
        if (matcher.find() && matcher.group(1).length() > 3) {
            throw invalidTime();
        }
        try {
            Instant instant = OffsetDateTime.parse(value).toInstant();
            if (!instant.isAfter(now)) {
                throw invalidTime();
            }
            return instant;
        } catch (DateTimeParseException exception) {
            throw invalidTime();
        }
    }

    private static TaskApiException invalidTime() {
        return new TaskApiException(HttpStatus.BAD_REQUEST, "INVALID_EXECUTE_AT",
                "executeAt must be a future ISO 8601 timestamp with an explicit offset and at most millisecond precision");
    }
}