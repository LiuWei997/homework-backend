package com.example.demo.scheduler;

import com.example.demo.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class DynamicScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DynamicScheduler.class);
    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.ofHours(8));
    private final TaskRepository repository;
    private final SchedulerWakeup wakeup;
    private final TaskDispatchService dispatchService;
    private final TaskRecoveryService recoveryService;
    private final Clock clock;
    private final Duration maxSleep;
    private final int batchSize;
    private volatile boolean running;
    private Thread thread;

    public DynamicScheduler(TaskRepository repository, SchedulerWakeup wakeup, TaskDispatchService dispatchService,
                            TaskRecoveryService recoveryService, Clock clock,
                            @Value("${scheduler.max-sleep:PT30S}") Duration maxSleep,
                            @Value("${scheduler.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.wakeup = wakeup;
        this.dispatchService = dispatchService;
        this.recoveryService = recoveryService;
        this.clock = clock;
        this.maxSleep = maxSleep;
        if (batchSize < 1) throw new IllegalArgumentException("scheduler.batch-size must be positive");
        this.batchSize = batchSize;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::runLoop, "task-scheduler");
        thread.setDaemon(false);
        log.info("Scheduler starting at {}; batchSize={}, maxSleep={}",
                formatLogTime(clock.instant()), batchSize, maxSleep);
        thread.start();
    }

    private void runLoop() {
        while (running) {
            long observedGeneration = wakeup.generation();
            try {
                Instant now = clock.instant();
                recoveryService.recoverExpiredLeases(now);
                drainDue(now);
                Instant scanNow = clock.instant();
                Optional<Instant> nextWakeAt = repository.findNextWakeAt();
                Duration wait = nextWakeAt
                        .map(next -> Duration.between(scanNow, next))
                        .map(duration -> duration.isNegative() || duration.isZero() ? Duration.ZERO
                                : duration.compareTo(maxSleep) > 0 ? maxSleep : duration)
                        .orElse(maxSleep);
                Duration actualWait = wait.isZero() ? Duration.ofMillis(10) : wait;
                Instant expectedWakeAt = scanNow.plus(actualWait);
                log.info("Scheduler sleeping: now={}, nextWakeAt={}, expectedWakeAt={}, wait={}",
                        formatLogTime(scanNow), nextWakeAt.map(DynamicScheduler::formatLogTime).orElse("none"),
                        formatLogTime(expectedWakeAt), actualWait);
                wakeup.awaitChange(observedGeneration, actualWait);
            } catch (InterruptedException exception) {
                if (running) log.info("Scheduler wait interrupted");
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                log.error("Scheduler iteration failed; retrying shortly", exception);
                try {
                    Instant retryAt = clock.instant();
                    Duration retryWait = Duration.ofSeconds(1);
                    log.info("Scheduler retry sleep: now={}, expectedWakeAt={}, wait={}",
                            formatLogTime(retryAt), formatLogTime(retryAt.plus(retryWait)), retryWait);
                    wakeup.awaitChange(observedGeneration, retryWait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static String formatLogTime(Instant instant) {
        return LOG_TIME_FORMAT.format(instant);
    }

    private void drainDue(Instant cutoff) {
        drainPending(cutoff);
        drainRetries(cutoff);
    }

    private void drainPending(Instant cutoff) {
        while (running) {
            if (!dispatchService.dispatchPendingBatch(cutoff, batchSize)) return;
        }
    }

    private void drainRetries(Instant cutoff) {
        while (running) {
            if (!dispatchService.dispatchRetryBatch(cutoff, batchSize)) return;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        wakeup.signal();
        if (thread != null) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) thread.interrupt();
        }
    }

    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
