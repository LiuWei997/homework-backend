package com.example.demo.scheduler;

import com.example.demo.cache.TaskDetailCache;
import com.example.demo.messaging.TaskMessagePublisher;
import com.example.demo.messaging.TaskTriggerEvent;
import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskRetryPolicy;
import com.example.demo.model.TaskStatus;
import com.example.demo.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TaskDispatchService {
    private static final Logger log = LoggerFactory.getLogger(TaskDispatchService.class);
    private final TaskRepository repository;
    private final TaskMessagePublisher publisher;
    private final TaskCacheFence cacheFence;
    private final Clock clock;
    private final Duration leaseDuration;

    public TaskDispatchService(TaskRepository repository, TaskMessagePublisher publisher,
                               TaskCacheFence cacheFence, Clock clock,
                               @Value("${scheduler.publish-lease:PT2M}") Duration leaseDuration) {
        this.repository = repository;
        this.publisher = publisher;
        this.cacheFence = cacheFence;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    public boolean dispatchPendingBatch(Instant cutoff, int batchSize) {
        Instant now = clock.instant();
        String leaseToken = UUID.randomUUID().toString();
        List<ScheduledTask> claimed = repository.claimDuePendingBatch(cutoff, batchSize, leaseToken,
                now.plus(leaseDuration), now);
        if (claimed.isEmpty()) return false;
        invalidateCacheBatch(claimed, false);

        Instant publishAt = clock.instant();
        List<ScheduledTask> publishing = repository.beginInitialPublishBatch(
                claimed.stream().map(ScheduledTask::id).toList(), leaseToken,
                publishAt.plus(leaseDuration), publishAt);
        invalidateCacheBatch(publishing, false);
        publishBatch(publishing, leaseToken);
        return true;
    }

    public boolean dispatchRetryBatch(Instant cutoff, int batchSize) {
        Instant now = clock.instant();
        String leaseToken = UUID.randomUUID().toString();
        List<ScheduledTask> publishing = repository.claimDueRetryBatch(cutoff, 1, leaseToken,
                now.plus(leaseDuration), now);
        if (publishing.isEmpty()) return false;
        invalidateCacheBatch(publishing, false);
        publishBatch(publishing, leaseToken);
        return true;
    }

    private void publishBatch(List<ScheduledTask> tasks, String leaseToken) {
        if (tasks.isEmpty()) return;
        Instant attemptedAt = clock.instant();
        List<TaskTriggerEvent> events = tasks.stream().map(task -> new TaskTriggerEvent(
                task.taskId() + ":" + task.scheduleVersion(), "TASK_TRIGGERED", task.taskId(),
                task.executeAt(), attemptedAt, task.payload())).toList();
        try {
            publisher.publishBatch(events);
        } catch (RuntimeException exception) {
            recordBatchFailure(tasks, leaseToken, exception);
            return;
        }

        Instant acknowledgedAt = clock.instant();
        int updated = repository.markTriggeredBatch(tasks.stream().map(ScheduledTask::id).toList(),
                leaseToken, acknowledgedAt);
        if (updated > 0) {
            invalidateCacheBatch(tasks, true);
        }
        log.info("Task batch publish acknowledged count={} stateUpdated={} eventIds={}",
                tasks.size(), updated, events.stream().map(TaskTriggerEvent::eventId).toList());
    }

    private void recordBatchFailure(List<ScheduledTask> tasks, String leaseToken, RuntimeException exception) {
        Instant failedAt = clock.instant();
        String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        if (error.length() > 1000) error = error.substring(0, 1000);
        int updated = repository.markPublishFailureBatch(tasks.stream().map(ScheduledTask::id).toList(),
                leaseToken, failedAt, error);
        if (updated > 0) {
            invalidateCacheBatch(tasks, true);
        }
        long exhausted = tasks.stream().filter(task -> TaskRetryPolicy.statusAfterFailure(task.publishAttempts())
                == TaskStatus.FAILED).count();
        log.warn("Task publish failed count={} stateUpdated={} retryCount={} failedCount={} errorType={}",
                tasks.size(), updated, tasks.size() - exhausted, exhausted, exception.getClass().getSimpleName());
    }

    private void invalidateCacheBatch(List<ScheduledTask> tasks, boolean stateUpdateCommitted) {
        cacheFence.invalidateAll(tasks.stream()
                .map(task -> new TaskDetailCache.Invalidation(task.taskId(),
                        task.lockVersion() + (stateUpdateCommitted ? 1 : 0)))
                .toList());
    }
}
