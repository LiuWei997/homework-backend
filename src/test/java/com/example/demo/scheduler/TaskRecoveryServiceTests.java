package com.example.demo.scheduler;

import com.example.demo.cache.TaskDetailCache;
import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import com.example.demo.repository.TaskRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRecoveryServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private TaskRepository repository;
    private TaskCacheFence cacheFence;
    private TaskRecoveryService service;

    @BeforeEach
    void setUp() {
        repository = mock(TaskRepository.class);
        cacheFence = mock(TaskCacheFence.class);
        service = new TaskRecoveryService(repository, cacheFence, 50);
    }

    @Test
    void returnsExpiredProcessingTaskToPendingUsingItsLeaseVersion() {
        ScheduledTask task = task(TaskStatus.PROCESSING, 12, 0, "lease-a");
        AtomicBoolean processingBatchRead = new AtomicBoolean();
        when(repository.recoverExpiredProcessingBatch(NOW, 50)).thenAnswer(invocation ->
                processingBatchRead.compareAndSet(false, true) ? List.of(task) : List.of());
        when(repository.recoverExpiredPublishingBatch(NOW, 50,
                "Publish lease expired; broker delivery outcome is unknown")).thenReturn(List.of());

        service.recoverExpiredLeases(NOW);

        verify(repository).recoverExpiredProcessingBatch(NOW, 50);
        verify(cacheFence).invalidateAll(List.of(new TaskDetailCache.Invalidation("job-recovery", 13L)));
    }

    @Test
    void recoversExpiredPublishingLeaseForTerminalFailurePolicy() {
        ScheduledTask task = task(TaskStatus.PUBLISHING, 80, 100, "lease-b");
        when(repository.recoverExpiredProcessingBatch(NOW, 50)).thenReturn(List.of());
        AtomicBoolean publishingBatchRead = new AtomicBoolean();
        when(repository.recoverExpiredPublishingBatch(NOW, 50,
                "Publish lease expired; broker delivery outcome is unknown")).thenAnswer(invocation ->
                publishingBatchRead.compareAndSet(false, true) ? List.of(task) : List.of());

        service.recoverExpiredLeases(NOW);

        verify(repository).recoverExpiredPublishingBatch(NOW, 50,
                "Publish lease expired; broker delivery outcome is unknown");
        verify(cacheFence).invalidateAll(List.of(new TaskDetailCache.Invalidation("job-recovery", 81L)));
    }

    private ScheduledTask task(TaskStatus status, long lockVersion, int attempts, String token) {
        return new ScheduledTask(1, "job-recovery", NOW.minusSeconds(10),
                JsonNodeFactory.instance.objectNode().put("type", "test"), status, lockVersion, 4,
                NOW.minusSeconds(60), NOW.minusSeconds(10), null, null, token,
                NOW.minusSeconds(1), attempts);
    }
}
