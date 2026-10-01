package com.example.demo.repository;

import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    ScheduledTask insert(String taskId, Instant executeAt, String payloadJson, Instant now);

    Optional<ScheduledTask> findByTaskId(String taskId);

    List<ScheduledTask> findPage(TaskStatus status, boolean futureOnly, Instant now, int limit, int offset);

    long count(TaskStatus status, boolean futureOnly, Instant now);

    boolean update(String taskId, long expectedLockVersion, Instant executeAt, String payloadJson, Instant now);

    boolean cancel(String taskId, long expectedLockVersion, Instant now);

    Optional<Instant> findNextWakeAt();

    List<ScheduledTask> claimDuePendingBatch(Instant cutoff, int limit, String leaseToken,
                                             Instant leaseUntil, Instant now);

    List<ScheduledTask> beginInitialPublishBatch(List<Long> ids, String leaseToken,
                                                 Instant leaseUntil, Instant now);

    List<ScheduledTask> claimDueRetryBatch(Instant cutoff, int limit, String leaseToken,
                                           Instant leaseUntil, Instant now);

    int markTriggeredBatch(List<Long> ids, String leaseToken, Instant triggeredAt);

    int markPublishFailureBatch(List<Long> ids, String leaseToken, Instant now,
                                String error);

    List<ScheduledTask> recoverExpiredProcessingBatch(Instant now, int limit);

    List<ScheduledTask> recoverExpiredPublishingBatch(Instant now, int limit, String error);
}
