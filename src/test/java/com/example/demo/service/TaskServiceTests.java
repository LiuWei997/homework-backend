package com.example.demo.service;

import com.example.demo.cache.TaskDetailCache;
import com.example.demo.dto.CreateTaskRequest;
import com.example.demo.dto.UpdateTaskRequest;
import com.example.demo.exception.TaskApiException;
import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import com.example.demo.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskServiceTests {
    private final Instant now = Instant.parse("2026-10-01T00:00:00Z");
    private final TaskRepository repository = mock(TaskRepository.class);
    private final TaskDetailCache cache = mock(TaskDetailCache.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private TaskService service(int limit) {
        return new TaskService(repository, cache, mapper, Clock.fixed(now, ZoneOffset.UTC),
                mock(ApplicationEventPublisher.class), limit);
    }

    private ScheduledTask task() {
        return new ScheduledTask(1, "job-a", now.plusSeconds(60), mapper.createObjectNode(), TaskStatus.PENDING,
                1, 1, now, now, null, null, null, null, 0);
    }

    @Test
    void normalizesCreateAndEveryIdLookup() {
        when(repository.insert(eq("job-a"), any(), anyString(), eq(now))).thenReturn(task());
        when(repository.findByTaskId("job-a")).thenReturn(Optional.of(task()));
        when(repository.update(eq("job-a"), eq(1L), any(), anyString(), eq(now))).thenReturn(true);
        when(repository.cancel("job-a", 1, now)).thenReturn(true);
        TaskService service = service(262144);
        assertThat(service.create(new CreateTaskRequest("JOB-A", now.plusSeconds(60).toString(),
                mapper.createObjectNode())).taskId()).isEqualTo("job-a");
        service.get("Job-A");
        verify(cache).get("job-a");
        service.update("JOB-A", new UpdateTaskRequest(now.plusSeconds(90).toString(), mapper.createObjectNode()));
        service.cancel("JOB-A");
        verify(repository).cancel("job-a", 1, now);
        verify(repository, never()).findByTaskId("JOB-A");
    }

    @Test
    void measuresUtf8PayloadBytesAndRejectsCreateAndUpdateBeforeDbWrite() {
        var payload = mapper.createObjectNode().put("text", "中".repeat(10));
        when(repository.findByTaskId("job-a")).thenReturn(Optional.of(task()));
        TaskService service = service(30);
        assertThatThrownBy(() -> service.create(new CreateTaskRequest("job-a", now.plusSeconds(60).toString(), payload)))
                .isInstanceOfSatisfying(TaskApiException.class, error -> assertThat(error.code()).isEqualTo("PAYLOAD_TOO_LARGE"));
        assertThatThrownBy(() -> service.update("JOB-A", new UpdateTaskRequest(now.plusSeconds(60).toString(), payload)))
                .isInstanceOf(TaskApiException.class);
        verify(repository, never()).insert(anyString(), any(), anyString(), any());
        verify(repository, never()).update(anyString(), anyLong(), any(), anyString(), any());
    }

    @Test
    void acceptsPayloadExactlyAtTheByteLimit() {
        when(repository.insert(anyString(), any(), eq("{}"), any())).thenReturn(task());
        service(2).create(new CreateTaskRequest("job-a", now.plusSeconds(60).toString(), mapper.createObjectNode()));
        verify(repository).insert(eq("job-a"), any(), eq("{}"), eq(now));
    }
}
