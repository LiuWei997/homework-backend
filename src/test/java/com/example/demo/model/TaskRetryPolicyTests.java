package com.example.demo.model;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class TaskRetryPolicyTests {
    @Test
    void capsBackoffAtSixteenSeconds() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(TaskRetryPolicy.backoff(attempt))
                    .isEqualTo(Duration.ofSeconds(new long[]{2, 4, 8, 16, 16}[attempt - 1]));
        }
    }

    @Test
    void permitsThreeIndividualRetriesAfterTheInitialPublish() {
        for (int attempt = 1; attempt < 4; attempt++) {
            assertThat(TaskRetryPolicy.statusAfterFailure(attempt)).isEqualTo(TaskStatus.PUBLISH_RETRY_WAIT);
        }
        assertThat(TaskRetryPolicy.statusAfterFailure(4)).isEqualTo(TaskStatus.FAILED);
        assertThat(TaskRetryPolicy.statusAfterFailure(100)).isEqualTo(TaskStatus.FAILED);
    }
}
