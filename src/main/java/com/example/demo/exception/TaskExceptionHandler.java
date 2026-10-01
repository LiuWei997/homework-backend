package com.example.demo.exception;

import com.example.demo.dto.TaskErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;

@RestControllerAdvice
public class TaskExceptionHandler {
    private final Clock clock;

    public TaskExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(TaskApiException.class)
    public ResponseEntity<TaskErrorResponse> handleTaskException(TaskApiException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<TaskErrorResponse> handleValidation(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<TaskErrorResponse> handleDatabaseFailure(DataAccessException exception, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "A required data service is temporarily unavailable", request);
    }

    private ResponseEntity<TaskErrorResponse> response(HttpStatus status, String code, String message,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(status).body(new TaskErrorResponse(clock.instant(), status.value(), code,
                message, request.getRequestURI()));
    }
}