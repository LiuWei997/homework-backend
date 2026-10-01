package com.example.demo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank @Size(max = 128) String taskId,
        @NotBlank String executeAt,
        @NotNull JsonNode payload
) {
}
