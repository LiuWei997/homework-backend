package com.example.demo.service;

import com.example.demo.cache.TaskDetailCache;
import com.example.demo.dto.CreateTaskRequest;
import com.example.demo.dto.TaskPageResponse;
import com.example.demo.dto.TaskResponse;
import com.example.demo.dto.UpdateTaskRequest;
import com.example.demo.event.ScheduleChanged;
import com.example.demo.exception.TaskApiException;
import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import com.example.demo.repository.TaskRepository;
import com.example.demo.util.TaskIdCodec;
import com.example.demo.util.TaskTimeCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final int MAX_SIZE = 100;

    private final TaskRepository repository;
    private final TaskDetailCache cache;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final int maxPayloadBytes;

    public TaskService(TaskRepository repository, TaskDetailCache cache, ObjectMapper objectMapper,
                       Clock clock, ApplicationEventPublisher events,
                       @Value("${task.max-payload-bytes:262144}") int maxPayloadBytes) {
        this.repository = repository;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.events = events;
        if (maxPayloadBytes < 1 || maxPayloadBytes > 512 * 1024) {
            throw new IllegalArgumentException("task.max-payload-bytes must be between 1 and 524288");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Transactional
    public TaskResponse create(CreateTaskRequest request) {
        Instant now = clock.instant();
        String taskId = TaskIdCodec.normalize(request.taskId());
        Instant executeAt = TaskTimeCodec.parseFutureInstant(request.executeAt(), now);
        String payloadJson = serializePayload(request.payload());
        try {
            ScheduledTask task = repository.insert(taskId, executeAt, payloadJson, now);
            publishChanged(task);
            return TaskResponse.from(task);
        } catch (DuplicateKeyException exception) {
            throw new TaskApiException(HttpStatus.CONFLICT, "TASK_ALREADY_EXISTS", "taskId already exists");
        }
    }

    @Transactional(readOnly = true)
    public TaskResponse get(String taskId) {
        taskId = TaskIdCodec.normalize(taskId);
        try {
            var cached = cache.get(taskId);
            if (cached.isPresent()) {
                return cached.get().response();
            }
        } catch (DataAccessException exception) {
            log.warn("Redis detail cache unavailable; falling back to MySQL");
        }

        ScheduledTask task = findTask(taskId);
        TaskResponse response = TaskResponse.from(task);
        try {
            cache.put(taskId, task.lockVersion(), response);
        } catch (DataAccessException exception) {
            log.warn("Could not populate Redis detail cache");
        }
        return response;
    }

    @Transactional(readOnly = true)
    public TaskPageResponse list(String statusValue, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new TaskApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "page must be non-negative and size must be between 1 and 100");
        }

        TaskStatus status = null;
        boolean futureOnly = false;
        if (statusValue != null && !statusValue.isBlank()) {
            if ("pending".equalsIgnoreCase(statusValue)) {
                status = TaskStatus.PENDING;
                futureOnly = true;
            } else {
                try {
                    status = TaskStatus.valueOf(statusValue.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new TaskApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unknown task status");
                }
            }
        }

        Instant now = clock.instant();
        int offset = Math.multiplyExact(page, size);
        List<TaskResponse> content = repository.findPage(status, futureOnly, now, size, offset)
                .stream().map(TaskResponse::from).toList();
        long total = repository.count(status, futureOnly, now);
        int totalPages = (int) ((total + size - 1) / size);
        return new TaskPageResponse(content, page, size, total, totalPages, page + 1 < totalPages);
    }

    @Transactional
    public TaskResponse update(String taskId, UpdateTaskRequest request) {
        taskId = TaskIdCodec.normalize(taskId);
        ScheduledTask current = findTask(taskId);
        Instant now = clock.instant();
        Instant executeAt = TaskTimeCodec.parseFutureInstant(request.executeAt(), now);
        String payloadJson = serializePayload(request.payload());
        if (!repository.update(taskId, current.lockVersion(), executeAt, payloadJson, now)) {
            throw notUpdatable(taskId);
        }
        ScheduledTask updated = findTask(taskId);
        publishChanged(updated);
        return TaskResponse.from(updated);
    }

    @Transactional
    public TaskResponse cancel(String taskId) {
        taskId = TaskIdCodec.normalize(taskId);
        ScheduledTask current = findTask(taskId);
        Instant now = clock.instant();
        if (!repository.cancel(taskId, current.lockVersion(), now)) {
            throw new TaskApiException(HttpStatus.CONFLICT, "TASK_NOT_CANCELLABLE", "Task state changed; reload and retry");
        }
        ScheduledTask cancelled = findTask(taskId);
        publishChanged(cancelled);
        return TaskResponse.from(cancelled);
    }

    private ScheduledTask findTask(String taskId) {
        return repository.findByTaskId(taskId).orElseThrow(() ->
                new TaskApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task was not found"));
    }

    private void publishChanged(ScheduledTask task) {
        events.publishEvent(new ScheduleChanged(task.taskId(), task.lockVersion()));
    }

    private String serializePayload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new TaskApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "payload must be a JSON object");
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
                throw new TaskApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                        "Serialized payload exceeds " + maxPayloadBytes + " UTF-8 bytes");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new TaskApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "payload is not valid JSON");
        }
    }

    private TaskApiException notUpdatable(String taskId) {
        if (repository.findByTaskId(taskId).isEmpty()) {
            return new TaskApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task was not found");
        }
        return new TaskApiException(HttpStatus.CONFLICT, "TASK_NOT_UPDATABLE", "Task state changed; reload and retry");
    }
}
