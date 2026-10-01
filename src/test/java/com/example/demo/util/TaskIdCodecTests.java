package com.example.demo.util;

import com.example.demo.exception.TaskApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskIdCodecTests {
    @Test
    void preservesTheUserIdExceptForLowercaseNormalization() {
        assertThat(TaskIdCodec.normalize("Job_A.01:RUN")).isEqualTo("job_a.01:run");
    }

    @Test
    void rejectsUnaddressableIdsInsteadOfSilentlyTrimmingThem() {
        for (String id : new String[]{"", " a", "a ", "a/b", "a?b", "中文", "a".repeat(129)}) {
            assertThatThrownBy(() -> TaskIdCodec.normalize(id)).isInstanceOf(TaskApiException.class);
        }
        assertThat(TaskIdCodec.normalize("a".repeat(128))).hasSize(128);
    }
}
