package com.example.demo.util;

import com.example.demo.exception.TaskApiException;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.regex.Pattern;

public final class TaskIdCodec {
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");

    private TaskIdCodec() { }

    public static String normalize(String value) {
        if (value == null) throw invalidId();
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!VALID_ID.matcher(normalized).matches()) throw invalidId();
        return normalized;
    }

    private static TaskApiException invalidId() {
        return new TaskApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK_ID",
                "taskId must contain 1-128 ASCII letters, digits, '.', '_', ':', or '-', starting with a letter or digit");
    }
}
