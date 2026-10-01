package com.example.demo.messaging;

import java.util.List;

public interface TaskMessagePublisher {
    void publishBatch(List<TaskTriggerEvent> events);
}
