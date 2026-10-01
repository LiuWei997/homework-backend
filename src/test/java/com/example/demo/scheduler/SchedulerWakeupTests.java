package com.example.demo.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerWakeupTests {
    @Test
    void signalBetweenDatabaseCheckAndWaitIsNotLost() throws InterruptedException {
        SchedulerWakeup wakeup = new SchedulerWakeup();
        long observedGeneration = wakeup.generation();

        wakeup.signal();
        wakeup.awaitChange(observedGeneration, Duration.ofSeconds(30));

        assertThat(wakeup.generation()).isEqualTo(observedGeneration + 1);
    }

    @Test
    void zeroTimeoutReturnsWithoutWaiting() throws InterruptedException {
        SchedulerWakeup wakeup = new SchedulerWakeup();
        long observedGeneration = wakeup.generation();

        wakeup.awaitChange(observedGeneration, Duration.ZERO);

        assertThat(wakeup.generation()).isEqualTo(observedGeneration);
    }
}
