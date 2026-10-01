package com.example.demo.util;

import com.example.demo.exception.TaskApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskTimeCodecTests {
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void parsesFutureOffsetTimestampAsAnInstant() {
        Instant parsed = TaskTimeCodec.parseFutureInstant("2026-09-30T15:00:00.125+03:00", NOW);

        assertThat(parsed).isEqualTo(Instant.parse("2026-09-30T12:00:00.125Z"));
    }

    @Test
    void rejectsTimestampsWithoutAnExplicitOffset() {
        assertInvalid("2026-09-30T13:00:00");
    }

    @Test
    void rejectsNowAndPastInstants() {
        assertInvalid("2026-09-30T12:00:00Z");
        assertInvalid("2026-09-30T11:59:59.999Z");
    }

    @Test
    void rejectsPrecisionBeyondDatabaseMilliseconds() {
        assertInvalid("2026-09-30T12:00:00.0001Z");
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> TaskTimeCodec.parseFutureInstant(value, NOW))
                .isInstanceOf(TaskApiException.class)
                .satisfies(exception -> assertThat(((TaskApiException) exception).code())
                        .isEqualTo("INVALID_EXECUTE_AT"));
    }
}
