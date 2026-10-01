package com.example.demo.scheduler;

import com.example.demo.cache.TaskDetailCache;
import com.example.demo.messaging.TaskMessagePublisher;
import com.example.demo.messaging.TaskTriggerEvent;
import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import com.example.demo.repository.TaskRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskDispatchServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant EXECUTE_AT = NOW.minusSeconds(1);

    private TaskRepository repository;
    private TaskMessagePublisher publisher;
    private TaskCacheFence cacheFence;
    private TaskDispatchService service;

    @BeforeEach
    void setUp() {
        repository = mock(TaskRepository.class);
        publisher = mock(TaskMessagePublisher.class);
        cacheFence = mock(TaskCacheFence.class);
        service = new TaskDispatchService(repository, publisher, cacheFence,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(15));
    }

    @Test
    void doesNotPublishWhenNoPendingTasksCanBeClaimed() {
        when(repository.claimDuePendingBatch(eq(NOW), eq(50), anyString(), eq(NOW.plusSeconds(15)), eq(NOW)))
                .thenReturn(List.of());

        assertThat(service.dispatchPendingBatch(NOW, 50)).isFalse();

        verify(publisher, never()).publishBatch(anyList());
    }

    @Test
    void claimsPublishesAndMarksTheBatchTriggered() {
        ScheduledTask processing = task(10, 5, 8, TaskStatus.PROCESSING, 0, "batch-token");
        ScheduledTask publishing = task(10, 6, 8, TaskStatus.PUBLISHING, 1, "batch-token");
        when(repository.claimDuePendingBatch(eq(NOW), eq(50), anyString(), eq(NOW.plusSeconds(15)), eq(NOW)))
                .thenReturn(List.of(processing));
        when(repository.beginInitialPublishBatch(eq(List.of(10L)), anyString(),
                eq(NOW.plusSeconds(15)), eq(NOW))).thenReturn(List.of(publishing));
        when(repository.markTriggeredBatch(eq(List.of(10L)), anyString(), eq(NOW))).thenReturn(1);

        assertThat(service.dispatchPendingBatch(NOW, 50)).isTrue();

        var events = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(publisher).publishBatch(events.capture());
        @SuppressWarnings("unchecked")
        List<TaskTriggerEvent> published = events.getValue();
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().eventId()).isEqualTo("job-a:8");
        assertThat(published.getFirst().taskId()).isEqualTo("job-a");
        assertThat(published.getFirst().executeAt()).isEqualTo(EXECUTE_AT);
        assertThat(published.getFirst().publishAttemptedAt()).isEqualTo(NOW);
        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).beginInitialPublishBatch(eq(List.of(10L)), token.capture(),
                eq(NOW.plusSeconds(15)), eq(NOW));
        verify(repository).markTriggeredBatch(List.of(10L), token.getValue(), NOW);
        verify(cacheFence).invalidateAll(List.of(new TaskDetailCache.Invalidation("job-a", 5L)));
        verify(cacheFence).invalidateAll(List.of(new TaskDetailCache.Invalidation("job-a", 6L)));
        verify(cacheFence).invalidateAll(List.of(new TaskDetailCache.Invalidation("job-a", 7L)));
    }

    @Test
    void failedInitialBatchRecordsEveryTaskTogetherWithoutMarkingTriggered() {
        List<ScheduledTask> processing = List.of(task(10, 5, 8, TaskStatus.PROCESSING, 0, "token"),
                task(11, 5, 9, TaskStatus.PROCESSING, 0, "token"));
        List<ScheduledTask> publishing = List.of(task(10, 6, 8, TaskStatus.PUBLISHING, 1, "token"),
                task(11, 6, 9, TaskStatus.PUBLISHING, 1, "token"));
        when(repository.claimDuePendingBatch(eq(NOW), eq(50), anyString(),
                eq(NOW.plusSeconds(15)), eq(NOW))).thenReturn(processing);
        when(repository.beginInitialPublishBatch(eq(List.of(10L, 11L)), anyString(),
                eq(NOW.plusSeconds(15)), eq(NOW))).thenReturn(publishing);
        org.mockito.Mockito.doThrow(new IllegalStateException("batch failed"))
                .when(publisher).publishBatch(anyList());

        assertThat(service.dispatchPendingBatch(NOW, 50)).isTrue();

        verify(repository).markPublishFailureBatch(eq(List.of(10L, 11L)), anyString(), eq(NOW),
                org.mockito.ArgumentMatchers.argThat(error -> error.contains("batch failed")));
        verify(repository, never()).markTriggeredBatch(anyList(), anyString(), any());
    }

    @Test
    void databaseFailureAfterAckLeavesLeaseForRecoveryInsteadOfRecordingSendFailure() {
        when(repository.claimDueRetryBatch(eq(NOW), eq(1), anyString(),
                eq(NOW.plusSeconds(15)), eq(NOW)))
                .thenReturn(List.of(task(10, 20, 8, TaskStatus.PUBLISHING, 2, "token")));
        when(repository.markTriggeredBatch(eq(List.of(10L)), anyString(), eq(NOW)))
                .thenThrow(new IllegalStateException("database offline"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.dispatchRetryBatch(NOW, 50))
                .hasMessage("database offline");

        verify(publisher).publishBatch(anyList());
        verify(repository, never()).markPublishFailureBatch(anyList(), anyString(), any(), anyString());
    }

    @Test
    void retriesOneTaskAtATimeAndRecordsFailure() {
        ScheduledTask retry = task(10, 20, 8, TaskStatus.PUBLISHING, 4, "batch-token");
        when(repository.claimDueRetryBatch(eq(NOW), eq(1), anyString(), eq(NOW.plusSeconds(15)), eq(NOW)))
                .thenReturn(List.of(retry));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker offline"))
                .when(publisher).publishBatch(anyList());
        when(repository.markPublishFailureBatch(eq(List.of(10L)), anyString(), eq(NOW),
                anyString())).thenReturn(1);

        assertThat(service.dispatchRetryBatch(NOW, 50)).isTrue();

        verify(repository).markPublishFailureBatch(eq(List.of(10L)), anyString(), eq(NOW),
                org.mockito.ArgumentMatchers.argThat(message -> message.contains("broker offline")));
        verify(repository, never()).markTriggeredBatch(anyList(), anyString(), any());
        verify(cacheFence).invalidateAll(List.of(new TaskDetailCache.Invalidation("job-a", 20L)));
        verify(cacheFence).invalidateAll(List.of(new TaskDetailCache.Invalidation("job-a", 21L)));
    }

    private ScheduledTask task(long id, long lockVersion, long scheduleVersion, TaskStatus status,
                               int attempts, String leaseToken) {
        return new ScheduledTask(id, "job-a", EXECUTE_AT,
                JsonNodeFactory.instance.objectNode().put("type", "email"), status, lockVersion,
                scheduleVersion, NOW.minusSeconds(30), NOW, null, null, leaseToken, null, attempts);
    }
}
