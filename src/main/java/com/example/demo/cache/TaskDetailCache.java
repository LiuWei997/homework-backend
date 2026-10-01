package com.example.demo.cache;

import com.example.demo.dto.TaskResponse;

import java.util.List;
import java.util.Optional;

public interface TaskDetailCache {
    Optional<CachedTaskResponse> get(String taskId);

    void put(String taskId, long lockVersion, TaskResponse response);

    void invalidate(String taskId, long lockVersion);

    void invalidateAll(List<Invalidation> invalidations);

    record Invalidation(String taskId, long lockVersion) {
    }

    record CachedTaskResponse(long lockVersion, TaskResponse response) {
    }
}
