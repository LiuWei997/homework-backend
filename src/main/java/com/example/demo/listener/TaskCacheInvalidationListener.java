package com.example.demo.listener;

import com.example.demo.cache.TaskDetailCache;
import com.example.demo.event.ScheduleChanged;
import com.example.demo.scheduler.SchedulerWakeup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TaskCacheInvalidationListener {
    private static final Logger log = LoggerFactory.getLogger(TaskCacheInvalidationListener.class);
    private final TaskDetailCache cache;
    private final SchedulerWakeup wakeup;

    public TaskCacheInvalidationListener(TaskDetailCache cache, SchedulerWakeup wakeup) {
        this.cache = cache;
        this.wakeup = wakeup;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleChanged(ScheduleChanged event) {
        try {
            cache.invalidate(event.taskId(), event.lockVersion());
        } catch (DataAccessException exception) {
            log.warn("Redis cache invalidation failed after task commit; TTL will bound staleness. taskId={}",
                    event.taskId());
        } finally {
            wakeup.signal();
        }
    }
}
