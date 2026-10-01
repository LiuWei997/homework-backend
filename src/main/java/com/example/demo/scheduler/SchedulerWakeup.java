package com.example.demo.scheduler;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class SchedulerWakeup {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private long generation;

    public long generation() {
        lock.lock();
        try {
            return generation;
        } finally {
            lock.unlock();
        }
    }

    public void signal() {
        lock.lock();
        try {
            generation++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void awaitChange(long observedGeneration, Duration timeout) throws InterruptedException {
        long remaining = timeout.toNanos();
        lock.lockInterruptibly();
        try {
            while (generation == observedGeneration && remaining > 0) {
                remaining = changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }
}
