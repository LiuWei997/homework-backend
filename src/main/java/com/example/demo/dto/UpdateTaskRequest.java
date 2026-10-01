package com.example.demo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateTaskRequest(
        @NotBlank String executeAt,
        @NotNull JsonNode payload
) {
}
