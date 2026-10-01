package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record TaskErrorResponse(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "GMT+08:00") Instant timestamp,
        int status,
        String code,
        String message,
        String path
) {
}
