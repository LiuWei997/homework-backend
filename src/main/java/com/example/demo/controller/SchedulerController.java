package com.example.demo.controller;

import com.example.demo.scheduler.SchedulerWakeup;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/scheduler")
public class SchedulerController {
    private final SchedulerWakeup schedulerWakeup;

    public SchedulerController(SchedulerWakeup schedulerWakeup) {
        this.schedulerWakeup = schedulerWakeup;
    }

    @PostMapping("/wakeup")
    public ResponseEntity<Void> wakeup() {
        schedulerWakeup.signal();
        return ResponseEntity.noContent().build();
    }
}
