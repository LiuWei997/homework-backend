package com.example.demo.exception;

import org.springframework.http.HttpStatus;

public class TaskApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public TaskApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
