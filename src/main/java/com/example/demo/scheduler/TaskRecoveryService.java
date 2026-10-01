package com.example.demo.scheduler;

import com.example.demo.cache.TaskDetailCache;
import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskRetryPolicy;
import com.example.demo.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TaskRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryService.class);
    private final TaskRepository repository;
    private final TaskCacheFence cacheFence;
    private final int batchSize;

    public TaskRecoveryService(TaskRepository repository, TaskCacheFence cacheFence,
                               @Value("${scheduler.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.cacheFence = cacheFence;
        if (batchSize < 1) throw new IllegalArgumentException("scheduler.batch-size must be positive");
        this.batchSize = batchSize;
    }

    public void recoverExpiredLeases(Instant now) {
        recoverProcessing(now);
        recoverPublishing(now);
    }

    private void recoverProcessing(Instant now) {
        List<ScheduledTask> expired;
        do {
            expired = repository.recoverExpiredProcessingBatch(now, batchSize);
            invalidateCacheBatch(expired);
            if (!expired.isEmpty()) {
                log.info("Recovered expired processing lease batch count={} state=PENDING", expired.size());
            }
        } while (expired.size() == batchSize);
    }

    private void recoverPublishing(Instant now) {
        List<ScheduledTask> expired;
        do {
            expired = repository.recoverExpiredPublishingBatch(now, batchSize,
                    "Publish lease expired; broker delivery outcome is unknown");
            invalidateCacheBatch(expired);
            if (!expired.isEmpty()) {
                long failed = expired.stream().filter(task -> TaskRetryPolicy.statusAfterFailure(task.publishAttempts())
                        == com.example.demo.model.TaskStatus.FAILED).count();
                log.warn("Recovered expired publishing lease batch count={} retryCount={} failedCount={}",
                        expired.size(), expired.size() - failed, failed);
            }
        } while (expired.size() == batchSize);
    }

    private void invalidateCacheBatch(List<ScheduledTask> tasks) {
        cacheFence.invalidateAll(tasks.stream()
                .map(task -> new TaskDetailCache.Invalidation(task.taskId(), task.lockVersion() + 1))
                .toList());
    }
}
