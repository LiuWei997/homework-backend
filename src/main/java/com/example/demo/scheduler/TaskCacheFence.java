package com.example.demo.scheduler;

import com.example.demo.cache.TaskDetailCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskCacheFence {
    private static final Logger log = LoggerFactory.getLogger(TaskCacheFence.class);
    private final TaskDetailCache cache;

    public TaskCacheFence(TaskDetailCache cache) {
        this.cache = cache;
    }

    public void invalidate(String taskId, long lockVersion) {
        invalidateAll(List.of(new TaskDetailCache.Invalidation(taskId, lockVersion)));
    }

    public void invalidateAll(List<TaskDetailCache.Invalidation> invalidations) {
        if (invalidations.isEmpty()) return;
        try {
            cache.invalidateAll(invalidations);
        } catch (DataAccessException exception) {
            log.warn("Could not invalidate task detail cache after state transition; taskCount={}",
                    invalidations.size());
        }
    }
}
