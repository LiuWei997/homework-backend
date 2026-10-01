package com.example.demo.messaging;

public class TaskPublishException extends RuntimeException {
    public TaskPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
